package incari.via;

import com.viaversion.viaversion.api.connection.StorableObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionProvider;

/**
 * Tells Via which version the server behind a connection speaks. On a server Via would ask the
 * server itself; here each connection was told when it was set up ({@link ViaPipeline#install}),
 * by the player's choice or the server list's ping.
 */
final class ServerVersions implements VersionProvider {

    @Override
    public ProtocolVersion getClosestServerProtocol(UserConnection connection) {
        Target target = connection.get(Target.class);
        if (target == null) {
            throw new IllegalStateException("A Via connection without a server version");
        }
        return target.version();
    }

    /** The server version of one connection, kept in Via's per-connection storage. */
    record Target(ProtocolVersion version) implements StorableObject {}
}
