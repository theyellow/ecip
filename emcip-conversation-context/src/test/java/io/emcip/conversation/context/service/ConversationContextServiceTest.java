package io.emcip.conversation.context.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.emcip.common.events.EventSchemas;
import io.emcip.conversation.context.entity.Message;
import io.emcip.conversation.context.entity.Message.MessageRole;
import io.emcip.conversation.context.entity.MessageThread;
import io.emcip.conversation.context.entity.User;
import io.emcip.conversation.context.repository.MessageRepository;
import io.emcip.conversation.context.repository.MessageThreadRepository;
import io.emcip.conversation.context.repository.UserRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConversationContextServiceTest {

    @Mock private MessageRepository messageRepository;
    @Mock private MessageThreadRepository threadRepository;
    @Mock private UserRepository userRepository;

    private ConversationContextService buildService() {
        return new ConversationContextService(messageRepository, threadRepository, userRepository);
    }

    private EventSchemas.TelegramMessageEvent telegramEvent(
            String senderId, Boolean isOutgoing, Map<String, Object> metadata) {
        return new EventSchemas.TelegramMessageEvent(
                "e1111111-1111-1111-1111-111111111111",
                "2026-01-01T00:00:00Z",
                "1.0.0",
                "TelegramMessage",
                999L, // telegramMessageId
                100L, // chatId
                senderId,
                "USER", // senderType
                "hi there", // text
                1_700_000_000, // date
                null, // editDate
                isOutgoing,
                42L, // replyToMessageId
                null, // replyInChatId
                metadata,
                null, // ingestedAt
                null, // senderDisplayName
                null, // senderUsername
                null // chatTitle
                );
    }

    @Test
    void processTelegramMessage_savesMessageWithThreadAndSender() {
        ConversationContextService svc = buildService();
        EventSchemas.TelegramMessageEvent event = telegramEvent("555", false, null);

        when(threadRepository.findById(100L)).thenReturn(Optional.empty());
        when(threadRepository.save(any(MessageThread.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(555L)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        Message result = svc.processTelegramMessage(event);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(messageCaptor.capture());
        Message saved = messageCaptor.getValue();

        assertThat(result).isSameAs(saved);
        assertThat(saved.getEventId()).isEqualTo("e1111111-1111-1111-1111-111111111111");
        assertThat(saved.getTelegramMessageId()).isEqualTo(999L);
        assertThat(saved.getTextContent()).isEqualTo("hi there");
        assertThat(saved.getSenderRole()).isEqualTo(MessageRole.USER);
        assertThat(saved.getIsEdited()).isFalse();
        assertThat(saved.getIsDeleted()).isFalse();
        assertThat(saved.getReplyToMessageId()).isEqualTo(42L);
        assertThat(saved.getTelegramTimestamp()).isEqualTo(Instant.ofEpochSecond(1_700_000_000));
        assertThat(saved.getThread().getTelegramChatId()).isEqualTo(100L);
        assertThat(saved.getSender().getTelegramId()).isEqualTo(555L);

        ArgumentCaptor<MessageThread> threadCaptor = ArgumentCaptor.forClass(MessageThread.class);
        verify(threadRepository, org.mockito.Mockito.atLeastOnce()).save(threadCaptor.capture());
        MessageThread createdThread = threadCaptor.getAllValues().get(0);
        assertThat(createdThread.getTelegramChatId()).isEqualTo(100L);
        assertThat(createdThread.getIsActive()).isTrue();
        assertThat(createdThread.getTitle()).isEqualTo("Chat 100");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getTelegramId()).isEqualTo(555L);
        assertThat(userCaptor.getValue().getIsBot()).isFalse();
    }

    @Test
    void processTelegramMessage_withNullSenderId_createsSystemUser() {
        ConversationContextService svc = buildService();
        EventSchemas.TelegramMessageEvent event = telegramEvent(null, true, null);

        when(threadRepository.findById(100L)).thenReturn(Optional.empty());
        when(threadRepository.save(any(MessageThread.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(0L)).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        Message result = svc.processTelegramMessage(event);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User systemUser = userCaptor.getValue();
        assertThat(systemUser.getTelegramId()).isEqualTo(0L);
        assertThat(systemUser.getUsername()).isEqualTo("system");
        assertThat(systemUser.getFirstName()).isEqualTo("System");
        assertThat(systemUser.getIsBot()).isFalse();

        assertThat(result.getSenderRole()).isEqualTo(MessageRole.SYSTEM);
    }

    @Test
    void updateIntentClassification_whenMessageFound_updatesAndSaves() {
        ConversationContextService svc = buildService();
        Message existing = new Message();
        existing.setEventId("src-1");
        when(messageRepository.findByEventId("src-1")).thenReturn(Optional.of(existing));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        EventSchemas.IntentClassifiedEvent event =
                new EventSchemas.IntentClassifiedEvent(
                        "e2222222-2222-2222-2222-222222222222",
                        "2026-01-01T00:00:00Z",
                        "1.0.0",
                        "IntentClassified",
                        "src-1",
                        "GREETING",
                        0.87,
                        null,
                        null);

        svc.updateIntentClassification(event);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        assertThat(captor.getValue().getIntentClassification()).isEqualTo("GREETING");
        assertThat(captor.getValue().getConfidenceScore()).isEqualTo(0.87);
    }

    @Test
    void updateIntentClassification_whenMessageNotFound_doesNotSave() {
        ConversationContextService svc = buildService();
        when(messageRepository.findByEventId("missing")).thenReturn(Optional.empty());

        EventSchemas.IntentClassifiedEvent event =
                new EventSchemas.IntentClassifiedEvent(
                        "e3333333-3333-3333-3333-333333333333",
                        "2026-01-01T00:00:00Z",
                        "1.0.0",
                        "IntentClassified",
                        "missing",
                        "GREETING",
                        0.5,
                        null,
                        null);

        svc.updateIntentClassification(event);

        verify(messageRepository, never()).save(any());
    }
}
