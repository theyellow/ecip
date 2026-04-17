// Stub implementation - Replace with real TDLib Java bindings
// This is a minimal stub for compilation only

package org.drinkless.tdlib;

public class TdApi {

    public abstract static class Object {
        public int getConstructor() {
            return 0;
        }
    }

    public abstract static class Function extends Object {}

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
    public static class SetLogVerbosityLevel extends Function {
        public static final int CONSTRUCTOR = -563916580;
        public int newLogVerbosityLevel;

        public SetLogVerbosityLevel(int level) {
            this.newLogVerbosityLevel = level;
        }
    }

    public static class SetTdlibParameters extends Function {
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

    public static class SetAuthenticationPhoneNumber extends Function {
        public static final int CONSTRUCTOR = -28558289;
        public String phoneNumber;
        public PhoneNumberAuthenticationSettings settings;

        public SetAuthenticationPhoneNumber(
                String phoneNumber, PhoneNumberAuthenticationSettings settings) {
            this.phoneNumber = phoneNumber;
            this.settings = settings;
        }
    }

    public static class CheckAuthenticationCode extends Function {
        public static final int CONSTRUCTOR = -1504062605;
        public String code;

        public CheckAuthenticationCode(String code) {
            this.code = code;
        }
    }

    public static class CheckAuthenticationPassword extends Function {
        public static final int CONSTRUCTOR = -333891141;
        public String password;

        public CheckAuthenticationPassword(String password) {
            this.password = password;
        }
    }

    public static class LogOut extends Function {
        public static final int CONSTRUCTOR = -1581923301;
    }

    // Helper classes
    public static class PhoneNumberAuthenticationSettings extends Object {
        public static final int CONSTRUCTOR = -1161236403;
        public boolean allowFlashCall;
        public boolean isCurrentPhoneNumber;
        public boolean allowSmsRetrieverApi;
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
        public long replyToMessageId;
        public long replyInChatId;
        public long messageThreadId;
        public boolean isChannelPost;
        public boolean isTopicMessage;
    }

    public abstract static class MessageContent extends Object {}

    public static class MessageText extends MessageContent {
        public static final int CONSTRUCTOR = 1989037971;
        public FormattedText text;
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

    public static class UpdateMessageDeleted extends Update {
        public static final int CONSTRUCTOR = 1663654771;
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
}
