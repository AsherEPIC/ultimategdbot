package com.github.alex1304.ultimategdbot.api.command.annotated;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.alex1304.ultimategdbot.api.command.Command;
import com.github.alex1304.ultimategdbot.api.command.CommandDocumentation;
import com.github.alex1304.ultimategdbot.api.command.CommandDocumentationEntry;
import com.github.alex1304.ultimategdbot.api.command.CommandFailedException;
import com.github.alex1304.ultimategdbot.api.command.Context;
import com.github.alex1304.ultimategdbot.api.command.FlagInformation;
import com.github.alex1304.ultimategdbot.api.command.PermissionLevel;
import com.github.alex1304.ultimategdbot.api.command.Scope;
import com.github.alex1304.ultimategdbot.api.utils.Markdown;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.function.TupleUtils;
import reactor.util.annotation.Nullable;
import reactor.util.function.Tuples;

/**
 * Command implemented via annotations.
 */
public class AnnotatedCommand implements Command {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(AnnotatedCommand.class);
	
	private final Object obj;
	private final Function<Context, Mono<Void>> action;
	private final Set<String> aliases;
	private final CommandDocumentation doc;
	private final PermissionLevel permLevel;
	private final Scope scope;

	private AnnotatedCommand(Object obj, Function<Context, Mono<Void>> action, Set<String> aliases,
			CommandDocumentation doc, PermissionLevel permLevel, Scope scope) {
		this.obj = obj;
		this.action = action;
		this.aliases = aliases;
		this.doc = doc;
		this.permLevel = permLevel;
		this.scope = scope;
	}

	@Override
	public Mono<Void> run(Context ctx) {
		return action.apply(ctx);
	}

	@Override
	public Set<String> getAliases() {
		return aliases;
	}
	
	@Override
	public CommandDocumentation getDocumentation() {
		return doc;
	}
	
	@Override
	public PermissionLevel getPermissionLevel() {
		return permLevel;
	}
	
	@Override
	public Scope getScope() {
		return scope;
	}
	
	@Override
	public String toString() {
		return "AnnotatedCommand{obj=" + obj.toString() + "}";
	}
	
	static AnnotatedCommand fromAnnotatedObject(Object obj, AnnotatedCommandProvider provider) {
		var cmdSpecAnnot = readCommandSpecAnnotation(obj);
		Method mainMethod = null;
		var subMethods = new HashMap<String, Method>();
		for (var method : obj.getClass().getMethods()) {
			method.setAccessible(true);
			var cmdActionAnnot = method.getAnnotation(CommandAction.class);
			if (cmdActionAnnot == null) {
				continue;
			}
			validateMethodPrototype(method);
			if (cmdActionAnnot.value().length == 0) {
				if (mainMethod != null) {
					throw new InvalidAnnotatedObjectException("Duplicate action declaration");
				}
				mainMethod = method;
			} else {
				for (var alias : cmdActionAnnot.value()) {
					if (subMethods.containsKey(alias)) {
						throw new InvalidAnnotatedObjectException("Duplicate subcommand declaration for '" + alias + "'");
					}
					subMethods.put(alias, method);
				}
			}
		}
		if (mainMethod == null && subMethods.isEmpty()) {
			throw new InvalidAnnotatedObjectException("No action defined for the command");
		}
		var mainMethodOptional = Optional.ofNullable(mainMethod); 
		return new AnnotatedCommand(obj,
				ctx -> {
					var args = ctx.getArgs();
					var firstArgIndex = new AtomicInteger(2);
					var matchingMethod = Optional.ofNullable(args.tokenCount() > 1 ? subMethods.get(args.get(1)) : null)
							.or(() -> {
								firstArgIndex.set(1);
								return mainMethodOptional;
							});
					var invalidSyntax = new CommandFailedException("Invalid syntax. See "
							+ Markdown.code(ctx.getPrefixUsed() + "help " + args.get(0)) + " for more information.");
					return Mono.justOrEmpty(matchingMethod)
							.switchIfEmpty(Mono.error(invalidSyntax))
							.flatMap(method -> {
								LOGGER.debug("Matching method: {}#{}", method.getDeclaringClass().getName(), method.getName());
								var parameterTypes = Flux.fromArray(method.getParameterTypes()).skip(1);
								var argTokens = Flux.fromIterable(args.getTokens(method.getParameters().length + firstArgIndex.get() - 1))
										.skip(firstArgIndex.get());
								return Flux.zip(parameterTypes, argTokens)
										.concatMap(TupleUtils.function((paramType, arg) -> provider.convert(ctx, arg, paramType)))
										.collectList()
										.defaultIfEmpty(List.of())
										.map(argList -> new ArrayList<Object>(argList))
										.doOnNext(argList -> {
											argList.add(0, ctx);
											// Filling missing arguments with null, or throw an exception if args aren't nullable
											while (argList.size() < method.getParameters().length) {
												if (!method.getParameters()[argList.size()].isAnnotationPresent(Nullable.class)) {
													throw invalidSyntax;
												}
												argList.add(null);
											}
										})
										.flatMap(argList -> Mono.fromCallable(() -> method.invoke(obj, argList.toArray())))
										.flatMap(mono -> (Mono<?>) mono);
							})
							.then();
				},
				Set.of(cmdSpecAnnot.aliases()),
				buildDocumentation(cmdSpecAnnot.shortDescription(), mainMethod, subMethods),
				cmdSpecAnnot.permLevel(),
				cmdSpecAnnot.scope());
	}

	private static CommandSpec readCommandSpecAnnotation(Object obj) {
		var cmdSpecAnnot = obj.getClass().getAnnotation(CommandSpec.class);
		if (cmdSpecAnnot == null) {
			throw new InvalidAnnotatedObjectException("@CommandSpec annotation is missing");
		}
		if (cmdSpecAnnot.aliases().length == 0) {
			throw new InvalidAnnotatedObjectException("@CommandSpec does not define any alias for the command");
		}
		return cmdSpecAnnot;
	}
	
	private static void validateMethodPrototype(Method method) {
		if (method.getReturnType() != Mono.class) {
			throw new InvalidAnnotatedObjectException("The return type of a command action method must be " + Mono.class.getName());
		}
		var paramTypes = method.getParameterTypes();
		if (paramTypes.length == 0 || paramTypes[0] != Context.class) {
			throw new InvalidAnnotatedObjectException("The first parameter of a command action method must be of type "
					+ Context.class.getName());
		}
	}
	
	private static CommandDocumentation buildDocumentation(String shortDescription, Method mainMethod, Map<String, Method> subMethods) {
		var methodsToProcess = new HashMap<String, Method>(subMethods);
		if (mainMethod != null) {
			methodsToProcess.put("", mainMethod);
		}
		var docEntries = new HashMap<String, CommandDocumentationEntry>();
		methodsToProcess.forEach((name, method) -> {
			var syntax = Arrays.stream(method.getParameters())
					.skip(1)
					.map(param -> Tuples.of(param.getName(), param.isAnnotationPresent(Nullable.class)))
					.map(TupleUtils.function((paramName, isNullable) -> isNullable ? "[" + paramName + "]" : "<" + paramName + ">"))
					.collect(Collectors.joining(" "));
			var cmdDocAnnot = method.getAnnotation(CommandDoc.class);
			var description = cmdDocAnnot == null ? "" : cmdDocAnnot.value();
			var flagInfo = new HashMap<String, FlagInformation>();
			var flagDocAnnot = method.getAnnotation(FlagDoc.class);
			if (flagDocAnnot != null) {
				for (var flag : flagDocAnnot.value()) {
					flagInfo.put(flag.name(), new FlagInformation(flag.valueFormat(), flag.description()));
				}
			}
			docEntries.put(name, new CommandDocumentationEntry(syntax, description, flagInfo));
		});
		return new CommandDocumentation(shortDescription, docEntries);
	}
}
