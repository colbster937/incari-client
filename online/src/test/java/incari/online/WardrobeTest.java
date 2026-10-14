package incari.online;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import incari.framework.online.IncariPlayer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WardrobeTest {

    private static final String PASSWORD = "correct horse battery";
    private static final UUID ALEX = new UUID(1, 1);
    private static final UUID STEVE = new UUID(2, 2);
    private static final Cosmetic FROST = cosmetic("cape.frost");
    private static final Cosmetic EMBER = cosmetic("cape.ember");
    private static final Cosmetic CROWN = cosmetic("hat.crown");
    private static final Cosmetic WAVE = cosmetic("emote.wave");

    private final FakeService fake = new FakeService();

    private static Cosmetic cosmetic(String id) {
        return Objects.requireNonNull(Catalog.byId(id));
    }

    private Client started() {
        Client client = new Client(fake);
        client.service.start();
        return client.settle();
    }

    private Client signedUp(Client client, String username) {
        Result<List<String>> result = client.await(done -> client.service.signUp(username, PASSWORD, done));
        assertThat(result.ok()).isTrue();
        return client.settle();
    }

    /** Lets the equip interval pass and settles. */
    private static void later(Client client) {
        client.now += OnlineService.EQUIP_INTERVAL_MILLIS;
        client.settle();
    }

    private List<JsonObject> sent(String type) {
        return fake.received.stream()
                .filter(m -> m.get("t").getAsString().equals(type))
                .toList();
    }

    @Test
    void theCatalogIsConsistent() {
        for (Cosmetic each : Catalog.all()) {
            assertThat(Catalog.byId(each.id())).isSameAs(each);
            assertThat(each.id()).matches("[a-z]+\\.[a-z0-9_]+");
        }
        for (Cosmetic.Slot slot : Cosmetic.Slot.values()) {
            assertThat(Catalog.inSlot(slot)).isNotEmpty();
        }
    }

    @Test
    void aLoadoutKeepsOnePerSlotAndLeavesOutWhatItCantDraw() {
        Loadout loadout = Loadout.of(List.of("cape.frost", "cape.ember", "hat.crown", "emote.wave", "hat.unknown_hat"));
        assertThat(loadout.ids()).containsExactly("cape.frost", "hat.crown");
        assertThat(loadout.with(EMBER).ids()).containsExactly("cape.ember", "hat.crown");
        assertThat(loadout.without(Cosmetic.Slot.CAPE).ids()).containsExactly("hat.crown");
        assertThat(Loadout.of(List.of())).isSameAs(Loadout.EMPTY);
    }

    @Test
    void whatsWornWithoutAnAccountIsKeptOnTheDevice() {
        Client client = started();
        client.service.wardrobe().wear(FROST);
        client.service.wardrobe().setShowOthers(false);
        client.service.wardrobe().saveOutfit("Snowy");
        assertThat(client.service.wardrobe().shared()).isFalse();
        assertThat(sent("equip")).isEmpty();

        Client again = new Client(fake, client.storage);
        Wardrobe wardrobe = again.service.wardrobe();
        assertThat(wardrobe.worn().ids()).containsExactly("cape.frost");
        assertThat(wardrobe.showOthers()).isFalse();
        assertThat(wardrobe.outfits()).extracting(Wardrobe.Outfit::name).containsExactly("Snowy");
    }

    @Test
    void aNewAccountTakesWhatTheDeviceWore() {
        Client client = started();
        client.service.wardrobe().wear(FROST);
        signedUp(client, "Frost");
        later(client);
        assertThat(client.service.wardrobe().shared()).isTrue();
        assertThat(fake.accounts.get("frost").equipped).containsExactly("cape.frost");
    }

    @Test
    void anAccountThatWearsSomethingReplacesTheDevices() {
        Client first = signedUp(started(), "Frost");
        first.service.wardrobe().wear(EMBER);
        later(first);

        Client other = started();
        other.service.wardrobe().wear(FROST);
        Result<Void> signedIn = other.await(done -> other.service.signIn("Frost", PASSWORD, done));
        assertThat(signedIn.ok()).isTrue();
        other.settle();
        assertThat(other.service.wardrobe().worn().ids()).containsExactly("cape.ember");
    }

    @Test
    void clicksAreGatheredIntoOneEquip() {
        Client client = signedUp(started(), "Frost");
        later(client);
        int before = sent("equip").size();
        Wardrobe wardrobe = client.service.wardrobe();
        wardrobe.wear(FROST);
        client.service.tick();
        wardrobe.wear(EMBER);
        wardrobe.wear(CROWN);
        client.settle();
        later(client);
        List<JsonObject> equips = sent("equip");
        assertThat(equips).hasSize(before + 2);
        assertThat(equips.get(equips.size() - 1).getAsJsonArray("cosmetics").toString())
                .isEqualTo("[\"cape.ember\",\"hat.crown\"]");
        later(client);
        assertThat(sent("equip")).hasSize(before + 2);
    }

    @Test
    void playersOnTheServerSeeAChangeAndTheAccountsOtherSessionsToo() {
        Client alex = signedUp(started(), "AlexAccount");
        Client steve = signedUp(started(), "SteveAccount");
        Client alexElsewhere = started();
        alexElsewhere.<Void>await(done -> alexElsewhere.service.signIn("AlexAccount", PASSWORD, done));
        Map<UUID, String> tab = Map.of(ALEX, "Alex", STEVE, "Steve");
        alex.service.joined("play.example.com", "Alex");
        alex.service.tabList(tab);
        alex.settle();
        steve.service.joined("play.example.com", "Steve");
        steve.service.tabList(tab);
        steve.settle();
        IncariPlayer seen = steve.service.player(ALEX);
        assertThat(seen).isNotNull();
        assertThat(seen.cosmetics()).isEmpty();

        alex.service.wardrobe().wear(CROWN);
        later(alex);
        steve.settle();
        alexElsewhere.settle();
        assertThat(Objects.requireNonNull(steve.service.player(ALEX)).cosmetics())
                .containsExactly("hat.crown");
        assertThat(alexElsewhere.service.wardrobe().worn().ids()).containsExactly("hat.crown");
    }

    @Test
    void emotesReachIncariPlayersOnTheSameServer() {
        Client alex = signedUp(started(), "AlexAccount");
        Client steve = signedUp(started(), "SteveAccount");
        Map<UUID, String> tab = Map.of(ALEX, "Alex", STEVE, "Steve");
        alex.service.joined("play.example.com", "Alex");
        alex.service.tabList(tab);
        alex.settle();
        steve.service.joined("play.example.com", "Steve");
        steve.service.tabList(tab);
        steve.settle();
        alex.settle();

        assertThat(alex.service.playEmote(WAVE)).isTrue();
        // One every two seconds.
        assertThat(alex.service.playEmote(WAVE)).isFalse();
        alex.settle();
        steve.settle();
        assertThat(steve.service.takeEmotes()).containsExactly(new OnlineService.PlayedEmote(ALEX, WAVE));
        assertThat(steve.service.takeEmotes()).isEmpty();
        assertThat(alex.service.takeEmotes()).isEmpty();
    }

    @Test
    void cosmeticsTheAccountOwnsAreOwnedOnlyWhileSignedIn() {
        // As one the backend gave the account, not every account.
        Cosmetic granted = new Cosmetic("cape.frost", Cosmetic.Slot.CAPE, "Frost", false);
        Client client = started();
        Wardrobe wardrobe = client.service.wardrobe();
        assertThat(wardrobe.owns(granted)).isFalse();
        signedUp(client, "Frost");
        assertThat(wardrobe.owns(granted)).isTrue();
        client.<Void>await(done -> client.service.signOut(false, done));
        assertThat(wardrobe.owns(granted)).isFalse();
    }

    @Test
    void aGrantArrivesAsAWardrobeEvent() {
        Client client = signedUp(started(), "Frost");
        long before = client.service.revision();
        fake.grant("Frost", "hat.crown");
        client.settle();
        assertThat(client.service.revision()).isNotEqualTo(before);
        assertThat(client.warnings).isEmpty();
    }

    @Test
    void aServiceWithoutCosmeticsLeavesThemOnTheDevice() {
        fake.cosmeticsSpoken = false;
        Client client = started();
        client.service.wardrobe().wear(FROST);
        signedUp(client, "Frost");
        later(client);
        assertThat(client.service.wardrobe().shared()).isFalse();
        assertThat(client.service.wardrobe().worn().ids()).containsExactly("cape.frost");
        assertThat(sent("equip")).isEmpty();
        assertThat(client.warnings).isEmpty();
    }

    @Test
    void changesMadeOfflineGoOnceTheConnectionIsBack() {
        Client client = signedUp(started(), "Frost");
        later(client);
        fake.last().drop();
        client.settle();
        assertThat(client.service.wardrobe().shared()).isFalse();
        client.service.wardrobe().wear(EMBER);
        client.now += 1_000;
        client.settle();
        later(client);
        assertThat(client.service.wardrobe().shared()).isTrue();
        assertThat(fake.accounts.get("frost").equipped).containsExactly("cape.ember");
    }

    @Test
    void outfitsAreReplacedByNameAndCapped() {
        Wardrobe wardrobe = started().service.wardrobe();
        wardrobe.wear(FROST);
        assertThat(wardrobe.saveOutfit("A")).isTrue();
        wardrobe.wear(EMBER);
        assertThat(wardrobe.saveOutfit("a")).isTrue();
        assertThat(wardrobe.outfits()).hasSize(1);
        assertThat(wardrobe.outfits().get(0).loadout().ids()).containsExactly("cape.ember");
        for (int i = 1; i < Wardrobe.MAX_OUTFITS; i++) {
            assertThat(wardrobe.saveOutfit("O" + i)).isTrue();
        }
        assertThat(wardrobe.saveOutfit("One too many")).isFalse();
        wardrobe.wear(Loadout.EMPTY);
        wardrobe.wear(wardrobe.outfits().get(0).loadout());
        assertThat(wardrobe.worn().ids()).containsExactly("cape.ember");
    }
}
