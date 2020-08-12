package org.togetherjava.discord.server;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.jshell.Diag;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.RestAction;
import org.jetbrains.annotations.NotNull;
import org.togetherjava.discord.server.execution.AllottedTimeExceededException;
import org.togetherjava.discord.server.execution.JShellSessionManager;
import org.togetherjava.discord.server.execution.JShellWrapper;
import org.togetherjava.discord.server.execution.JShellWrapper.JShellResult;
import org.togetherjava.discord.server.io.input.InputSanitizerManager;
import org.togetherjava.discord.server.rendering.RendererManager;

public class CommandHandler extends ListenerAdapter {

  private static final Pattern CODE_BLOCK_EXTRACTOR_PATTERN = Pattern
      .compile("```(java)?\\s*([\\w\\W]+)```");

  private final JShellSessionManager jShellSessionManager;
  private final String botPrefix;
  private final RendererManager rendererManager;
  private final boolean autoDeleteMessages;
  private final Duration autoDeleteMessageDuration;
  private final InputSanitizerManager inputSanitizerManager;
  private final int maxContextDisplayAmount;
  private final Map<Long, Message> answeredMessages = new HashMap<>();

  @SuppressWarnings("WeakerAccess")
  public CommandHandler(Config config) {
    this.jShellSessionManager = new JShellSessionManager(config);
    this.botPrefix = config.getString("prefix");
    this.rendererManager = new RendererManager();
    this.autoDeleteMessages = config.getBoolean("messages.auto_delete");
    this.autoDeleteMessageDuration = config.getDuration("messages.auto_delete.duration");
    this.inputSanitizerManager = new InputSanitizerManager();
    this.maxContextDisplayAmount = config.getInt("messages.max_context_display_amount");
  }

  @Override
  public void onMessageDelete(@NotNull MessageDeleteEvent event) {
    deleteOldMessage(event.getMessageIdLong());
  }

  @Override
  public void onGuildMessageUpdate(@NotNull GuildMessageUpdateEvent event) {
    deleteOldMessage(event.getMessageIdLong());
    handleMessage(event.getMessage());
  }

  @Override
  public void onGuildMessageReceived(@NotNull GuildMessageReceivedEvent event) {
    handleMessage(event.getMessage());
  }

  private void deleteOldMessage(Long requestMessageId) {
    Optional.ofNullable(answeredMessages.get(requestMessageId)).ifPresent(message -> message.delete().queue());
  }

  private void handleMessage(Message receivedMessage) {
    String message = receivedMessage.getContentRaw();

    if (message.startsWith(botPrefix)) {
      String command = parseCommandFromMessage(message);
      String authorID = receivedMessage.getAuthor().getId();

      JShellWrapper shell = jShellSessionManager.getSessionOrCreate(authorID);

      try {
        executeCommand(receivedMessage.getIdLong(), receivedMessage.getAuthor(), shell, command, receivedMessage.getTextChannel());
      } catch (UnsupportedOperationException | AllottedTimeExceededException e) {
        EmbedBuilder embedBuilder = buildCommonEmbed(receivedMessage.getAuthor(), null);
        rendererManager.renderObject(embedBuilder, e);
        send(receivedMessage.getIdLong(), receivedMessage.getChannel().sendMessage(new MessageBuilder().setEmbed(embedBuilder.build()).build()));
      }
    }
  }

  private String parseCommandFromMessage(String messageContent) {
    String withoutPrefix = messageContent.substring(botPrefix.length());

    Matcher codeBlockMatcher = CODE_BLOCK_EXTRACTOR_PATTERN.matcher(withoutPrefix);

    if (codeBlockMatcher.find()) {
      return codeBlockMatcher.group(2);
    }

    return inputSanitizerManager.sanitize(withoutPrefix);
  }

  private void executeCommand(Long requestMessageId, User user, JShellWrapper shell, String command,
                              MessageChannel channel) {
    List<JShellResult> evalResults = shell.eval(command);

    List<JShellResult> toDisplay = evalResults.subList(
        Math.max(0, evalResults.size() - maxContextDisplayAmount),
        evalResults.size()
    );

    for (JShellResult result : toDisplay) {
      handleResult(requestMessageId, user, result, shell, channel);
    }
  }

  private void handleResult(Long requestMessageId, User user, JShellWrapper.JShellResult result, JShellWrapper shell,
      MessageChannel channel) {
    MessageBuilder messageBuilder = new MessageBuilder();
    EmbedBuilder embedBuilder;

    try {
      SnippetEvent snippetEvent = result.getEvents().get(0);

      embedBuilder = buildCommonEmbed(user, snippetEvent.snippet());

      rendererManager.renderJShellResult(embedBuilder, result);

      Iterable<Diag> diagonstics = shell.getSnippetDiagnostics(snippetEvent.snippet())::iterator;
      for (Diag diag : diagonstics) {
        rendererManager.renderObject(embedBuilder, diag);
      }

    } catch (UnsupportedOperationException | AllottedTimeExceededException e) {
      embedBuilder = buildCommonEmbed(user, null);
      rendererManager.renderObject(embedBuilder, e);
      messageBuilder.setEmbed(embedBuilder.build());
    }

    messageBuilder.setEmbed(embedBuilder.build());
    send(requestMessageId, channel.sendMessage(messageBuilder.build()));
  }

  private EmbedBuilder buildCommonEmbed(User user, Snippet snippet) {
    EmbedBuilder embedBuilder = new EmbedBuilder()
        .setTitle(user.getName() + "'s Result");

    if (snippet != null) {
      embedBuilder.addField("Snippet-ID", "$" + snippet.id(), true);
    }

    return embedBuilder;
  }

  private void send(Long requestMessageId, RestAction<Message> action) {
    action.submit().thenAccept(message -> {
      answeredMessages.put(requestMessageId, message);
      if (autoDeleteMessages) {
        message.delete().delay(autoDeleteMessageDuration).queue();
      }
    });
  }
}
