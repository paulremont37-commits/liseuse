package fr.paulremont.liseuse;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;

import javax.net.SocketFactory;

/**
 * Sockets avec TCP_NODELAY : chaque petit message (un appui du Stream Deck) part tout de suite,
 * sans attendre d'être regroupé avec le suivant (algorithme de Nagle).
 */
final class NoDelaySocketFactory extends SocketFactory {
    private final SocketFactory base = SocketFactory.getDefault();

    private static Socket nd(Socket s) throws SocketException {
        s.setTcpNoDelay(true);
        return s;
    }

    @Override public Socket createSocket() throws IOException { return nd(base.createSocket()); }
    @Override public Socket createSocket(String host, int port) throws IOException { return nd(base.createSocket(host, port)); }
    @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return nd(base.createSocket(host, port, local, localPort)); }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return nd(base.createSocket(host, port)); }
    @Override public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) throws IOException { return nd(base.createSocket(address, port, local, localPort)); }
}
