package incari.online;

import static org.assertj.core.api.Assertions.assertThat;

import incari.framework.online.IncariPlayer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Friends, requests and messages (ADR 0059) against the fake service. */
class SocialTest {

    private static final String PASSWORD = "correct horse battery";
    private static final UUID ALEX = new UUID(1, 1);
    private static final UUID STEVE = new UUID(2, 2);

    private final FakeService fake = new FakeService();

    private Client signedUp(String username) {
        Client client = new Client(fake);
        client.service.start();
        client.settle();
        assertThat(client.<List<String>>await(done -> client.service.signUp(username, PASSWORD, done))
                        .ok())
                .isTrue();
        return client;
    }

    private Social.Friend friends(Client a, String bName, Client b) {
        assertThat(a.<Void>await(done -> a.service.befriend(bName, done)).ok()).isTrue();
        b.settle();
        Social.Request incoming = b.service.social().requests().get(0);
        assertThat(b.<Void>await(done -> b.service.accept(incoming, done)).ok()).isTrue();
        a.settle();
        return a.service.social().friends().get(0);
    }

    @Test
    void theFriendListLoadsOnSignIn() {
        Client client = signedUp("Frost");
        assertThat(client.service.social().available()).isTrue();
        assertThat(client.service.social().loaded()).isTrue();
        assertThat(client.service.social().friends()).isEmpty();
    }

    @Test
    void aServiceWithoutFriendsSaysSo() {
        fake.friendsSpoken = false;
        Client client = signedUp("Frost");
        assertThat(client.service.social().available()).isFalse();
    }

    @Test
    void aRequestByUsernameIsAcceptedAndBothBecomeFriends() {
        Client alex = signedUp("Alex");
        Client steve = signedUp("Steve");
        assertThat(alex.<Void>await(done -> alex.service.befriend("steve", done))
                        .ok())
                .isTrue();
        assertThat(alex.service.social().requests())
                .singleElement()
                .satisfies(request -> assertThat(request.incoming()).isFalse());
        steve.settle();
        Social.Request incoming = steve.service.social().requests().get(0);
        assertThat(incoming.incoming()).isTrue();
        assertThat(incoming.label()).isEqualTo("Alex");
        assertThat(steve.service.social().pendingRequests()).isEqualTo(1);
        assertThat(steve.service.social().takeNotices()).singleElement().satisfies(notice -> {
            assertThat(notice.request()).isTrue();
            assertThat(notice.who()).isEqualTo("Alex");
        });

        assertThat(steve.<Void>await(done -> steve.service.accept(incoming, done))
                        .ok())
                .isTrue();
        alex.settle();
        assertThat(steve.service.social().friends())
                .extracting(Social.Friend::username)
                .containsExactly("Alex");
        assertThat(alex.service.social().friends())
                .extracting(Social.Friend::username)
                .containsExactly("Steve");
        assertThat(alex.service.social().requests()).isEmpty();
        assertThat(steve.service.social().requests()).isEmpty();
    }

    @Test
    void askingSomeoneWhoAskedYouMakesYouFriends() {
        Client alex = signedUp("Alex");
        Client steve = signedUp("Steve");
        alex.<Void>await(done -> alex.service.befriend("Steve", done));
        steve.settle();
        assertThat(steve.<Void>await(done -> steve.service.befriend("Alex", done))
                        .ok())
                .isTrue();
        alex.settle();
        assertThat(steve.service.social().friends()).hasSize(1);
        assertThat(alex.service.social().friends()).hasSize(1);
        assertThat(alex.service.social().requests()).isEmpty();
    }

    @Test
    void requestsTurnedOffArriveNowhere() {
        Client alex = signedUp("Alex");
        Client steve = signedUp("Steve");
        assertThat(steve.<Void>await(done -> steve.service.setFriendRequests(false, done))
                        .ok())
                .isTrue();
        assertThat(steve.service.social().friendRequests()).isFalse();
        // Alex isn't told: the request looks sent.
        assertThat(alex.<Void>await(done -> alex.service.befriend("Steve", done))
                        .ok())
                .isTrue();
        steve.settle();
        assertThat(steve.service.social().requests()).isEmpty();
    }

    @Test
    void declineWithdrawsAndUnfriendRemoves() {
        Client alex = signedUp("Alex");
        Client steve = signedUp("Steve");
        alex.<Void>await(done -> alex.service.befriend("Steve", done));
        Social.Request sent = alex.service.social().requests().get(0);
        assertThat(alex.<Void>await(done -> alex.service.decline(sent, done)).ok())
                .isTrue();
        steve.settle();
        assertThat(steve.service.social().requests()).isEmpty();

        Social.Friend friend = friends(alex, "Steve", steve);
        assertThat(alex.<Void>await(done -> alex.service.unfriend(friend, done)).ok())
                .isTrue();
        steve.settle();
        assertThat(alex.service.social().friends()).isEmpty();
        assertThat(steve.service.social().friends()).isEmpty();
    }

    @Test
    void friendsMessageEachOther() {
        Client alex = signedUp("Alex");
        Client steve = signedUp("Steve");
        Social.Friend steveFriend = friends(alex, "Steve", steve);
        Social.Peer toSteve = new Social.Peer(true, steveFriend.fid(), steveFriend.username());

        assertThat(alex.<Void>await(done -> alex.service.sendMessage(toSteve, "  hi there  ", done))
                        .ok())
                .isTrue();
        List<Social.Message> sent = alex.service.social().conversation(toSteve).messages();
        assertThat(sent).singleElement().satisfies(message -> {
            assertThat(message.text()).isEqualTo("hi there");
            assertThat(message.mine()).isTrue();
            assertThat(message.delivery()).isEqualTo(Social.Delivery.SENT);
        });

        steve.settle();
        Social.Friend alexFriend = steve.service.social().friends().get(0);
        Social.Peer toAlex = new Social.Peer(true, alexFriend.fid(), alexFriend.username());
        assertThat(steve.service.social().unread()).isEqualTo(1);
        steve.service.social().takeNotices();
        steve.service.viewing(toAlex);
        assertThat(steve.service.social().unread()).isZero();

        // While Steve looks at the conversation, a new message arrives read, with no toast.
        alex.<Void>await(done -> alex.service.sendMessage(toSteve, "again", done));
        steve.settle();
        assertThat(steve.service.social().unread()).isZero();
        assertThat(steve.service.social().takeNotices()).isEmpty();
        assertThat(steve.service.social().conversation(toAlex).messages())
                .extracting(Social.Message::text)
                .containsExactly("hi there", "again");
    }

    @Test
    void messagesSentWhileDisconnectedArriveOnReconnect() {
        Client alex = signedUp("Alex");
        Client steve = signedUp("Steve");
        Social.Friend steveFriend = friends(alex, "Steve", steve);
        Social.Peer toSteve = new Social.Peer(true, steveFriend.fid(), steveFriend.username());
        Social.Friend alexFriend = steve.service.social().friends().get(0);
        Social.Peer toAlex = new Social.Peer(true, alexFriend.fid(), alexFriend.username());
        steve.service.viewing(toAlex);
        alex.<Void>await(done -> alex.service.sendMessage(toSteve, "one", done));
        steve.settle();

        // Steve's connection drops; Alex goes on talking.
        fake.sockets.stream()
                .filter(socket -> socket.open && socket.account != null && socket.account.username.equals("Steve"))
                .findFirst()
                .orElseThrow()
                .drop();
        steve.settle();
        alex.<Void>await(done -> alex.service.sendMessage(toSteve, "two", done));
        alex.<Void>await(done -> alex.service.sendMessage(toSteve, "three", done));
        steve.now += 60_000;
        steve.settle();

        assertThat(steve.service.status()).isEqualTo(incari.framework.online.Online.Status.SIGNED_IN);
        assertThat(steve.service.social().conversation(toAlex).messages())
                .extracting(Social.Message::text)
                .containsExactly("one", "two", "three");
        // Read up to the newest, now that it's held.
        String newest =
                steve.service.social().conversation(toAlex).messages().get(2).mid();
        assertThat(fake.received.stream()
                        .filter(message -> message.get("t").getAsString().equals("read"))
                        .reduce((first, second) -> second)
                        .orElseThrow()
                        .get("mid")
                        .getAsString())
                .isEqualTo(newest);
    }

    @Test
    void aConversationNotShownLoadsWhatItMissedWhenNextShown() {
        Client alex = signedUp("Alex");
        Client steve = signedUp("Steve");
        Social.Friend steveFriend = friends(alex, "Steve", steve);
        Social.Peer toSteve = new Social.Peer(true, steveFriend.fid(), steveFriend.username());
        Social.Friend alexFriend = steve.service.social().friends().get(0);
        Social.Peer toAlex = new Social.Peer(true, alexFriend.fid(), alexFriend.username());
        alex.<Void>await(done -> alex.service.sendMessage(toSteve, "one", done));
        steve.settle();
        steve.service.viewing(toAlex);
        steve.service.viewing(null);

        fake.sockets.stream()
                .filter(socket -> socket.open && socket.account != null && socket.account.username.equals("Steve"))
                .findFirst()
                .orElseThrow()
                .drop();
        steve.settle();
        alex.<Void>await(done -> alex.service.sendMessage(toSteve, "two", done));
        steve.now += 60_000;
        steve.settle();
        // The service's count, though the client held the conversation already read.
        assertThat(steve.service.social().unread()).isEqualTo(1);

        steve.service.viewing(toAlex);
        steve.service.loadOlder(toAlex);
        steve.settle();
        assertThat(steve.service.social().conversation(toAlex).messages())
                .extracting(Social.Message::text)
                .containsExactly("one", "two");
        assertThat(steve.service.social().unread()).isZero();
    }

    @Test
    void historyLoadsOlderPages() {
        Client alex = signedUp("Alex");
        Client steve = signedUp("Steve");
        Social.Friend steveFriend = friends(alex, "Steve", steve);
        Social.Peer toSteve = new Social.Peer(true, steveFriend.fid(), "Steve");
        for (int i = 0; i < FakeService.HISTORY_PAGE + 10; i++) {
            int n = i;
            alex.<Void>await(done -> alex.service.sendMessage(toSteve, "message " + n, done));
        }
        // A fresh client: the history comes from the service a page at a time.
        Client again = new Client(fake, alex.storage);
        again.service.start();
        again.settle();
        again.service.loadOlder(toSteve);
        again.settle();
        Social.Conversation conversation = again.service.social().conversation(toSteve);
        assertThat(conversation.messages()).hasSize(FakeService.HISTORY_PAGE);
        assertThat(conversation.hasMore()).isTrue();
        again.service.loadOlder(toSteve);
        again.settle();
        assertThat(conversation.messages()).hasSize(FakeService.HISTORY_PAGE + 10);
        assertThat(conversation.messages().get(0).text()).isEqualTo("message 0");
        assertThat(conversation.hasMore()).isFalse();
    }

    @Test
    void messagingTurnedOffTurnsMessagesAway() {
        Client alex = signedUp("Alex");
        Client steve = signedUp("Steve");
        Social.Friend steveFriend = friends(alex, "Steve", steve);
        steve.<Void>await(done -> steve.service.setMessagesFrom(Social.MessagesFrom.NOBODY, done));
        Social.Peer toSteve = new Social.Peer(true, steveFriend.fid(), "Steve");
        assertThat(alex.<Void>await(done -> alex.service.sendMessage(toSteve, "hello?", done))
                        .failure())
                .isEqualTo(Failure.CLOSED);
        assertThat(alex.service.social().conversation(toSteve).messages())
                .singleElement()
                .satisfies(message -> assertThat(message.delivery()).isEqualTo(Social.Delivery.FAILED));
    }

    @Test
    void playersOnTheServerMessageOnlyWhenTheyAllowEveryone() {
        Client alex = signedUp("Alex");
        Client steve = signedUp("Steve");
        Map<UUID, String> tab = Map.of(ALEX, "AlexMC", STEVE, "SteveMC");
        alex.service.joined("play.example.com", "AlexMC");
        alex.service.tabList(tab);
        steve.service.joined("play.example.com", "SteveMC");
        steve.service.tabList(tab);
        alex.settle();
        steve.settle();
        IncariPlayer steveHere = alex.service.player(STEVE);
        assertThat(steveHere).isNotNull();
        Social.Peer toSteve = new Social.Peer(false, steveHere.pid(), "SteveMC");

        // Friends only, by default.
        assertThat(alex.<Void>await(done -> alex.service.sendMessage(toSteve, "hey", done))
                        .failure())
                .isEqualTo(Failure.CLOSED);
        steve.<Void>await(done -> steve.service.setMessagesFrom(Social.MessagesFrom.EVERYONE, done));
        assertThat(alex.<Void>await(done -> alex.service.sendMessage(toSteve, "hey", done))
                        .ok())
                .isTrue();
        steve.settle();
        assertThat(steve.service.social().playersMessaged()).containsValue("AlexMC");
        assertThat(steve.service.social().unread()).isEqualTo(1);

        // Leaving the server ends what was said there.
        steve.service.left();
        assertThat(steve.service.social().playersMessaged()).isEmpty();
        assertThat(steve.service.social().unread()).isZero();
    }

    @Test
    void aRequestToAPlayerOnTheServerIsLabelledByTheirName() {
        Client alex = signedUp("Alex");
        Client steve = signedUp("Steve");
        Map<UUID, String> tab = Map.of(ALEX, "AlexMC", STEVE, "SteveMC");
        alex.service.joined("play.example.com", "AlexMC");
        alex.service.tabList(tab);
        steve.service.joined("play.example.com", "SteveMC");
        steve.service.tabList(tab);
        alex.settle();
        IncariPlayer steveHere = alex.service.player(STEVE);
        assertThat(steveHere).isNotNull();
        assertThat(alex.<Void>await(done -> alex.service.befriend("SteveMC", steveHere, done))
                        .ok())
                .isTrue();
        // The username stays Steve's to share: Alex sees the name they sent it to.
        assertThat(alex.service.social().requests().get(0).label()).isEqualTo("SteveMC");
        // Known by Steve's pid, whatever name another player here goes by.
        assertThat(alex.service.social().sentTo(new Social.Peer(false, steveHere.pid(), "SteveMC")))
                .isNotNull();
        assertThat(alex.service.social().sentTo(new Social.Peer(false, "someone-else", "SteveMC")))
                .isNull();
        steve.settle();
        assertThat(steve.service.social().requests().get(0).label()).isEqualTo("Alex");
        // A request received is never one Steve sent, even under a matching name.
        IncariPlayer alexHere = steve.service.player(ALEX);
        assertThat(alexHere).isNotNull();
        assertThat(steve.service.social().sentTo(new Social.Peer(false, alexHere.pid(), "Alex")))
                .isNull();
    }

    @Test
    void blockingAFriendEndsItOnBothSides() {
        Client alex = signedUp("Alex");
        Client steve = signedUp("Steve");
        Social.Friend steveFriend = friends(alex, "Steve", steve);
        assertThat(alex.<Void>await(done -> alex.service.blockFriend(steveFriend, done))
                        .ok())
                .isTrue();
        steve.settle();
        assertThat(alex.service.social().friends()).isEmpty();
        assertThat(steve.service.social().friends()).isEmpty();
    }

    @Test
    void askingTwiceIsOneRequest() {
        Client alex = signedUp("Alex");
        Client steve = signedUp("Steve");
        alex.<Void>await(done -> alex.service.befriend("Steve", done));
        alex.<Void>await(done -> alex.service.befriend("Steve", done));
        steve.settle();
        assertThat(alex.service.social().requests()).hasSize(1);
        assertThat(steve.service.social().requests()).hasSize(1);
    }

    @Test
    void signingOutForgetsEverything() {
        Client alex = signedUp("Alex");
        Client steve = signedUp("Steve");
        friends(alex, "Steve", steve);
        alex.<Void>await(done -> alex.service.signOut(false, done));
        assertThat(alex.service.social().friends()).isEmpty();
        assertThat(alex.service.social().loaded()).isFalse();
    }
}
