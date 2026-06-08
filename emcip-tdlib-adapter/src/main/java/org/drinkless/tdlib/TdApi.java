// Stub implementation - Replace with real TDLib Java bindings
// This is a minimal stub for compilation only

package org.drinkless.tdlib;

public class TdApi {

    public abstract static class Object {
        public int getConstructor() {
            return 0;
        }
    }

    public abstract static class Function<T extends Object> extends Object {}

    public abstract static class Update extends Object {}

    // Basic responses
    public static class Ok extends Object {
        public static final int CONSTRUCTOR = -722616727;
    }

    public static class Error extends Object {
        public static final int CONSTRUCTOR = -1679978726;
        public int code;
        public String message;
    }

    // Authorization states
    public abstract static class AuthorizationState extends Object {}

    public static class AuthorizationStateWaitTdlibParameters extends AuthorizationState {
        public static final int CONSTRUCTOR = 904720988;
    }

    public static class AuthorizationStateWaitPhoneNumber extends AuthorizationState {
        public static final int CONSTRUCTOR = 306402531;
    }

    public static class AuthorizationStateWaitCode extends AuthorizationState {
        public static final int CONSTRUCTOR = -526430106;
    }

    public static class AuthorizationStateWaitPassword extends AuthorizationState {
        public static final int CONSTRUCTOR = -48331319;
    }

    public static class AuthorizationStateReady extends AuthorizationState {
        public static final int CONSTRUCTOR = -18343641;
    }

    public static class AuthorizationStateLoggingOut extends AuthorizationState {
        public static final int CONSTRUCTOR = 1000504476;
    }

    public static class AuthorizationStateClosing extends AuthorizationState {
        public static final int CONSTRUCTOR = 445952761;
    }

    public static class AuthorizationStateClosed extends AuthorizationState {
        public static final int CONSTRUCTOR = 1526047584;
    }

    // Authorization functions
    public static class SetLogVerbosityLevel extends Function<Ok> {
        public static final int CONSTRUCTOR = -563916580;
        public int newLogVerbosityLevel;

        public SetLogVerbosityLevel(int level) {
            this.newLogVerbosityLevel = level;
        }
    }

    public static class SetTdlibParameters extends Function<Ok> {
        public static final int CONSTRUCTOR = -325358204;
        public boolean useTestDc;
        public String databaseDirectory;
        public String filesDirectory;
        public boolean useFileDatabase;
        public boolean useChatInfoDatabase;
        public boolean useMessageDatabase;
        public boolean useSecretChats;
        public int apiId;
        public String apiHash;
        public String systemLanguageCode;
        public String deviceModel;
        public String systemVersion;
        public String applicationVersion;
    }

    public static class SetAuthenticationPhoneNumber extends Function<Ok> {
        public static final int CONSTRUCTOR = -28558289;
        public String phoneNumber;
        public PhoneNumberAuthenticationSettings settings;

        public SetAuthenticationPhoneNumber(
                String phoneNumber, PhoneNumberAuthenticationSettings settings) {
            this.phoneNumber = phoneNumber;
            this.settings = settings;
        }
    }

    public static class CheckAuthenticationCode extends Function<Ok> {
        public static final int CONSTRUCTOR = -1504062605;
        public String code;

        public CheckAuthenticationCode(String code) {
            this.code = code;
        }
    }

    public static class CheckAuthenticationPassword extends Function<Ok> {
        public static final int CONSTRUCTOR = -333891141;
        public String password;

        public CheckAuthenticationPassword(String password) {
            this.password = password;
        }
    }

    public static class LogOut extends Function<Ok> {
        public static final int CONSTRUCTOR = -1581923301;
    }

    public static class Close extends Function<Ok> {
        public static final int CONSTRUCTOR = -1886400114;
    }

    // Helper classes
    public static class PhoneNumberAuthenticationSettings extends Object {
        public static final int CONSTRUCTOR = -1161236403;
        public boolean allowFlashCall;
        public boolean isCurrentPhoneNumber;
        public boolean allowSmsRetrieverApi;
    }

    // MessageReplyTo hierarchy (real TDLib API)
    public abstract static class MessageReplyTo extends Object {}

    public static class MessageReplyToMessage extends MessageReplyTo {
        public static final int CONSTRUCTOR = -1803845097;
        public long chatId;
        public long messageId;
    }

    // Message and Update classes
    public static class Message extends Object {
        public static final int CONSTRUCTOR = -1868563857;
        public long id;
        public MessageSender senderId;
        public long chatId;
        public MessageContent content;
        public int date;
        public int editDate;
        public boolean isOutgoing;
        public MessageReplyTo replyTo;
        public boolean isChannelPost;
    }

    public abstract static class MessageContent extends Object {}

    public static class MessageText extends MessageContent {
        public static final int CONSTRUCTOR = 1989037971;
        public FormattedText text;
    }

    public static class MessageSticker extends MessageContent {
        public static final int CONSTRUCTOR = 1509640442;
    }

    public static class MessagePhoto extends MessageContent {
        public static final int CONSTRUCTOR = -1967947295;
    }

    public static class MessageVideo extends MessageContent {
        public static final int CONSTRUCTOR = 2021281344;
    }

    public static class MessageAnimation extends MessageContent {
        public static final int CONSTRUCTOR = 1834601369;
    }

    public static class MessageDocument extends MessageContent {
        public static final int CONSTRUCTOR = 596945583;
    }

    public static class MessageAudio extends MessageContent {
        public static final int CONSTRUCTOR = 276722716;
    }

    public static class MessageVoiceNote extends MessageContent {
        public static final int CONSTRUCTOR = 527777781;
    }

    public static class MessageVideoNote extends MessageContent {
        public static final int CONSTRUCTOR = 1173892267;
    }

    public static class MessagePoll extends MessageContent {
        public static final int CONSTRUCTOR = -662504218;
    }

    public static class FormattedText extends Object {
        public static final int CONSTRUCTOR = -252624564;
        public String text;
        public TextEntity[] entities;
    }

    public static class TextEntity extends Object {
        public static final int CONSTRUCTOR = -1951688490;
        public int offset;
        public int length;
        public TextEntityType type;
    }

    public abstract static class TextEntityType extends Object {}

    // MessageSender
    public abstract static class MessageSender extends Object {}

    public static class MessageSenderUser extends MessageSender {
        public static final int CONSTRUCTOR = -336109341;
        public long userId;
    }

    public static class MessageSenderChat extends MessageSender {
        public static final int CONSTRUCTOR = -239660022;
        public long chatId;
    }

    // Update classes
    public static class UpdateAuthorizationState extends Update {
        public static final int CONSTRUCTOR = -1517589057;
        public AuthorizationState authorizationState;
    }

    public static class UpdateNewMessage extends Update {
        public static final int CONSTRUCTOR = -5632525;
        public Message message;
    }

    public static class UpdateMessageEdited extends Update {
        public static final int CONSTRUCTOR = -814662474;
        public long chatId;
        public long messageId;
        public int editDate;
    }

    public static class UpdateDeleteMessages extends Update {
        public static final int CONSTRUCTOR = 1579132440;
        public long chatId;
        public long[] messageIds;
        public boolean isPermanent;
        public boolean fromCache;
    }

    public static class UpdateChatTitle extends Update {
        public static final int CONSTRUCTOR = -175405113;
        public long chatId;
        public String title;
    }

    public static class UpdateUser extends Update {
        public static final int CONSTRUCTOR = -1451453015;
        public User user;
    }

    public static class User extends Object {
        public static final int CONSTRUCTOR = -1828424189;
        public long id;
        public String firstName;
        public String lastName;
        public String username;
        public String phoneNumber;
    }

    // Chat type hierarchy
    public abstract static class ChatType extends Object {}

    public static class ChatTypeSupergroup extends ChatType {
        public static final int CONSTRUCTOR = -1472570774;
        public long supergroupId;
        public boolean isChannel;
    }

    public static class ChatTypeBasicGroup extends ChatType {
        public static final int CONSTRUCTOR = 1239182448;
        public long basicGroupId;
    }

    public static class ChatTypePrivate extends ChatType {
        public static final int CONSTRUCTOR = 1579934391;
        public long userId;
    }

    public static class ChatTypeSecret extends ChatType {
        public static final int CONSTRUCTOR = 862366513;
        public int secretChatId;
        public long userId;
    }

    // Chat
    public static class Chat extends Object {
        public static final int CONSTRUCTOR = -861487386;
        public long id;
        public ChatType type;
        public String title;
    }

    // Chats (list of chat IDs)
    public static class Chats extends Object {
        public static final int CONSTRUCTOR = -1245016219;
        public int totalCount;
        public long[] chatIds;
    }

    // GetChats function
    public static class GetChats extends Function<Chats> {
        public static final int CONSTRUCTOR = -972768574;
        public ChatList chatList;
        public int limit;

        public GetChats(ChatList chatList, int limit) {
            this.chatList = chatList;
            this.limit = limit;
        }
    }

    // GetChat function
    public static class GetChat extends Function<Chat> {
        public static final int CONSTRUCTOR = 1866601536;
        public long chatId;

        public GetChat(long chatId) {
            this.chatId = chatId;
        }
    }

    // ChatList (abstract, null = main chat list)
    public abstract static class ChatList extends Object {}

    public static class ChatListMain extends ChatList {
        public static final int CONSTRUCTOR = -400991316;
    }

    // InputMessageContent hierarchy
    public abstract static class InputMessageContent extends Object {}

    public static class InputMessageText extends InputMessageContent {
        public static final int CONSTRUCTOR = 1447278813;
        public FormattedText text;
    }

    // InputMessageReplyTo hierarchy
    public abstract static class InputMessageReplyTo extends Object {}

    public static class InputMessageReplyToMessage extends InputMessageReplyTo {
        public static final int CONSTRUCTOR = -763431794;
        public long messageId;
    }

    // SendMessage function
    public static class SendMessage extends Function<Message> {
        public static final int CONSTRUCTOR = 504157374;
        public long chatId;
        public InputMessageReplyTo replyTo;
        public InputMessageContent inputMessageContent;
    }

    // CreatePrivateChat function
    public static class CreatePrivateChat extends Function<Chat> {
        public static final int CONSTRUCTOR = -1303995166;
        public long userId;
        public boolean force;

        public CreatePrivateChat(long userId, boolean force) {
            this.userId = userId;
            this.force = force;
        }
    }
}
