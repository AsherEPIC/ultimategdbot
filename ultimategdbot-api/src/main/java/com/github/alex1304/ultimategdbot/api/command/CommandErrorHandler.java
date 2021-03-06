package com.github.alex1304.ultimategdbot.api.command;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.alex1304.ultimategdbot.api.DatabaseException;
import com.github.alex1304.ultimategdbot.api.utils.BotUtils;
import com.github.alex1304.ultimategdbot.api.utils.Markdown;

import discord4j.rest.http.client.ClientException;
import reactor.core.publisher.Mono;

/**
 * Provides a convenient way to add error handlers for bot commands.
 */
public class CommandErrorHandler {
	private static final Logger LOGGER = LoggerFactory.getLogger(CommandErrorHandler.class);
	
	private final Map<Class<? extends Throwable>, BiFunction<Throwable, Context, Mono<Void>>> handlers = new LinkedHashMap<>();
	
	public CommandErrorHandler() {
		initDefaultHandlers();
	}
	
	/**
	 * Adds an error handler.
	 * 
	 * @param <T> the type of error to handle
	 * @param errorClass the type of error to handler
	 * @param handleAction the action to execute according to the error instance and the context
	 */
	@SuppressWarnings("unchecked")
	public <T extends Throwable> void addHandler(Class<T> errorClass, BiFunction<T, Context, Mono<Void>> handleAction) {
		handlers.put(errorClass, (error, ctx) -> handleAction.apply((T) error, ctx));
	}
	
	/**
	 * Applies the handler on the resulting Mono of {@link Command#run(Context)}.
	 * 
	 * @param commandMono the Mono returned by {@link Command#run(Context)}
	 * @param ctx the context in which the command was used
	 * @return a new Mono&lt;Void&gt; identical to the given commandMono but with the error handlers applied.
	 */
	public Mono<Void> apply(Mono<Void> commandMono, Context ctx) {
		for (var handler : handlers.entrySet()) {
			commandMono = commandMono.onErrorResume(handler.getKey(), e -> handler.getValue().apply(e, ctx));
		}
		return commandMono;
	}
	
	private void initDefaultHandlers() {
		addHandler(CommandFailedException.class, (e, ctx) -> ctx.reply(":no_entry_sign: " + e.getMessage()).then());
//		addHandler(InvalidSyntaxException.class, (e, ctx) -> ctx.reply(":no_entry_sign: Invalid syntax!"
//				+ "\n```\n" + ctx.getPrefixUsed() + ctx.getArgs().get(0) + " " + ctx.getCommand().getSyntax()
//				+ "\n```\n" + "See `" + ctx.getPrefixUsed() + "help " + ctx.getArgs().get(0) + "` for more information.").then());
		addHandler(PermissionDeniedException.class, (e, ctx) ->
				ctx.reply(":no_entry_sign: You are not granted the privileges to run this command.").then());
		addHandler(ClientException.class, (e, ctx) -> {
			LOGGER.debug("Discord ClientException thrown when using a command. User input: "
					+ ctx.getEvent().getMessage().getContent().orElse("") + ", Error:", e);
			var h = e.getErrorResponse();
			var sb = new StringBuilder();
			h.getFields().forEach((k, v) -> sb.append(k).append(": ").append(String.valueOf(v)).append("\n"));
			return ctx.reply(":no_entry_sign: Discord returned an error when executing this command: "
							+ Markdown.code(e.getStatus().code() + " " + e.getStatus().reasonPhrase()) + "\n"
							+ Markdown.codeBlock(sb.toString())
							+ (e.getStatus().code() == 403 ? "Make sure that I have sufficient permissions in this server and try again." : ""))
					.then();
		});
		addHandler(DatabaseException.class, (e, ctx) -> Mono.when(
				ctx.reply(":no_entry_sign: An error occured when accessing the database. Try again."),
				Mono.fromRunnable(() -> LOGGER.error("A database error occured", e)),
				BotUtils.debugError(":no_entry_sign: " + Markdown.bold("A database error occured."), ctx, e)));
	}
	
	@Override
	public String toString() {
		return "CommandErrorHandler{handledErrors=[" + handlers.keySet().stream()
				.map(Class::getName).collect(Collectors.joining(", ")) + "]}";
	}
}
