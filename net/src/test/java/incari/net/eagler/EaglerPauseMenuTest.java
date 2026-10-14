package incari.net.eagler;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EaglerPauseMenuTest {

    @Test
    void readsALinkButtonADiscordButtonAndTheirIcons() {
        ByteBuf in = Unpooled.buffer();
        in.writeByte(EaglerPauseMenu.MODE_EXTERNAL_URL | EaglerPauseMenu.MODE_EXTERNAL_URL << 4);
        Wire.writeString(in, "Carrotcraft Store");
        shortAscii(in, "https://store.example");
        Wire.writeString(in, "Our Discord");
        shortAscii(in, "https://discord.gg/example");
        // Two keys share one image; a third names one that isn't there.
        Wire.writeVarInt(in, 3);
        Wire.writeByteAscii(in, "icon_serverInfo_L");
        Wire.writeVarInt(in, 0);
        Wire.writeByteAscii(in, "icon_discord_L");
        Wire.writeVarInt(in, 0);
        Wire.writeByteAscii(in, "icon_title_L");
        Wire.writeVarInt(in, 5);
        Wire.writeVarInt(in, 1);
        // A 2x1 image: transparent, then white (the server writes white as 0xFFFF).
        in.writeByte(2);
        in.writeByte(1);
        in.writeShort(0);
        in.writeShort(0xFFFF);

        EaglerPauseMenu menu = EaglerPauseMenu.read(in);

        assertThat(menu.serverInfo())
                .isEqualTo(new EaglerPauseMenu.Button(
                        "Carrotcraft Store", EaglerPauseMenu.MODE_EXTERNAL_URL, "https://store.example"));
        assertThat(menu.discord()).isNotNull();
        assertThat(menu.discord().url()).isEqualTo("https://discord.gg/example");
        assertThat(menu.icons()).containsOnlyKeys("icon_serverInfo_L", "icon_discord_L");
        EaglerPauseMenu.Image icon = menu.icons().get("icon_discord_L");
        assertThat(icon.width()).isEqualTo(2);
        assertThat(icon.pixel(0, 0)).isZero();
        assertThat(icon.pixel(1, 0)).isEqualTo(0xFFFFFFFF);
        assertThat(in.isReadable()).isFalse();
    }

    @Test
    void readsAnEmbeddedServerInfoButtonAndNoIcons() {
        ByteBuf in = Unpooled.buffer();
        in.writeByte(EaglerPauseMenu.MODE_EMBED_DOWNLOAD);
        Wire.writeString(in, "Server Info");
        in.writeByte(0);
        Wire.writeString(in, "Server Info");
        in.writeZero(20);
        // The server writes a single 0 byte for no icons.
        in.writeByte(0);

        EaglerPauseMenu menu = EaglerPauseMenu.read(in);

        assertThat(menu.serverInfo())
                .isEqualTo(new EaglerPauseMenu.Button("Server Info", EaglerPauseMenu.MODE_EMBED_DOWNLOAD, null));
        assertThat(menu.discord()).isNull();
        assertThat(menu.icons()).isEmpty();
        assertThat(in.isReadable()).isFalse();
    }

    private static void shortAscii(ByteBuf out, String value) {
        out.writeShort(value.length());
        out.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }
}
