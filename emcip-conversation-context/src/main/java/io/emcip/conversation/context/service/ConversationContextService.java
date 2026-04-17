package io.emcip.conversation.context.service;

import io.emcip.common.events.EventSchemas;
import io.emcip.conversation.context.entity.Message;
import io.emcip.conversation.context.entity.Message.MessageRole;
import io.emcip.conversation.context.entity.MessageThread;
import io.emcip.conversation.context.entity.MessageThread.ThreadType;
import io.emcip.conversation.context.entity.User;
import io.emcip.conversation.context.repository.MessageRepository;
import io.emcip.conversation.context.repository.MessageThreadRepository;
import io.emcip.conversation.context.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing conversation context.
 * Persists Telegram messages, threads, and users from Kafka events.
 */
@Slf4j
@Service
public class ConversationContextService {

    private final MessageRepository messageRepository;
    private final MessageThreadRepository threadRepository;
    private final UserRepository userRepository;

    public ConversationContextService(
            MessageRepository messageRepository,
            MessageThreadRepository threadRepository,
            UserRepository userRepository) {
        this.messageRepository = messageRepository;
        this.threadRepository = threadRepository;
        this.userRepository = userRepository;
    }

    /**
     * Process a Telegram message event and persist to database.
     * Creates or updates thread, sender user, and message record.
     */
    @Transactional
    public Message processTelegramMessage(EventSchemas.TelegramMessageEvent event) {
        log.debug("Processing message event: {} from chat {}", event.eventId(), event.chatId());

        // Create or update thread
        MessageThread thread = getOrCreateThread(event);

        // Create or update sender user
        User sender = getOrCreateSender(event);

        // Determine sender role
        MessageRole role = determineSenderRole(event, sender);

        // Create message
        Message message = new Message();
        message.setEventId(event.eventId());
        message.setTelegramMessageId(event.telegramMessageId());
        message.setThread(thread);
        message.setSender(sender);
        message.setSenderRole(role);
        message.setTextContent(event.text() != null ? event.text() : "");
        message.setIsEdited(event.editDate() != null && event.editDate() > 0);
        message.setIsDeleted(false);
        message.setTelegramTimestamp(Instant.ofEpochSecond(event.date()));
        message.setReplyToMessageId(event.replyToMessageId());

        Message saved = messageRepository.save(message);
        log.info("Saved message {} from chat {} with role {}", 
                saved.getEventId(), thread.getTelegramChatId(), role);

        // Update thread's last message timestamp
        thread.setLastMessageAt(Instant.now());
        threadRepository.save(thread);

        return saved;
    }

    /**
     * Update message with intent classification.
     */
    @Transactional
    public void updateIntentClassification(EventSchemas.IntentClassifiedEvent event) {
        log.debug("Updating intent for source event: {}", event.sourceEventId());

        Optional<Message> messageOpt = messageRepository.findByEventId(event.sourceEventId());
        if (messageOpt.isPresent()) {
            Message message = messageOpt.get();
            message.setIntentClassification(event.intent());
            message.setConfidenceScore(event.confidence());
            messageRepository.save(message);
            log.info("Updated intent for message {}: {} (confidence: {})",
                    event.sourceEventId(), event.intent(), event.confidence());
        } else {
            log.warn("Message not found for intent update: {}", event.sourceEventId());
        }
    }

    private MessageThread getOrCreateThread(EventSchemas.TelegramMessageEvent event) {
        return threadRepository.findById(event.chatId())
                .orElseGet(() -> {
                    MessageThread newThread = new MessageThread();
                    newThread.setTelegramChatId(event.chatId());
                    newThread.setThreadType(determineThreadType(event));
                    newThread.setIsActive(true);
                    newThread.setTitle(extractThreadTitle(event));
                    
                    if (event.metadata() != null && event.metadata().get("memberCount") instanceof Number count) {
                        newThread.setMemberCount(count.intValue());
                    }
                    
                    log.info("Creating new thread: {}", event.chatId());
                    return threadRepository.save(newThread);
                });
    }

    private User getOrCreateSender(EventSchemas.TelegramMessageEvent event) {
        if (event.senderId() == null) {
            // Create anonymous user for system messages
            return userRepository.findById(0L)
                    .orElseGet(() -> {
                        User system = new User();
                        system.setTelegramId(0L);
                        system.setUsername("system");
                        system.setFirstName("System");
                        system.setIsBot(false);
                        return userRepository.save(system);
                    });
        }

        Long senderId = Long.parseLong(event.senderId());
        return userRepository.findById(senderId)
                .orElseGet(() -> {
                    User newUser = new User();
                    newUser.setTelegramId(senderId);
                    newUser.setIsBot("BOT".equals(event.senderType()));
                    
                    // Extract user info from metadata if available
                    if (event.metadata() != null) {
                        if (event.metadata().get("username") instanceof String username) {
                            newUser.setUsername(username);
                        }
                        if (event.metadata().get("firstName") instanceof String firstName) {
                            newUser.setFirstName(firstName);
                        }
                        if (event.metadata().get("lastName") instanceof String lastName) {
                            newUser.setLastName(lastName);
                        }
                    }
                    
                    log.info("Creating new user: {}", senderId);
                    return userRepository.save(newUser);
                });
    }

    private MessageRole determineSenderRole(EventSchemas.TelegramMessageEvent event, User sender) {
        if (Boolean.TRUE.equals(event.isOutgoing())) {
            return MessageRole.SYSTEM; // Our own messages
        }
        if ("BOT".equals(event.senderType()) || Boolean.TRUE.equals(sender.getIsBot())) {
            return MessageRole.BOT;
        }
        // Check for admin role in metadata
        if (event.metadata() != null && Boolean.TRUE.equals(event.metadata().get("isAdmin"))) {
            return MessageRole.ADMIN;
        }
        return MessageRole.USER;
    }

    private ThreadType determineThreadType(EventSchemas.TelegramMessageEvent event) {
        if (event.metadata() == null) {
            return ThreadType.UNKNOWN;
        }
        if (Boolean.TRUE.equals(event.metadata().get("isChannelPost"))) {
            return ThreadType.CHANNEL;
        }
        if (Boolean.TRUE.equals(event.metadata().get("isGroup"))) {
            return ThreadType.GROUP;
        }
        return ThreadType.PRIVATE;
    }

    private String extractThreadTitle(EventSchemas.TelegramMessageEvent event) {
        if (event.metadata() != null && event.metadata().get("chatTitle") instanceof String title) {
            return title;
        }
        return "Chat " + event.chatId();
    }
}
