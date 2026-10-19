package dev.pocketpaas.domain.ssh

/**
 * `sshd -D` için argv listesi üretir. Değerler ayrı argv öğeleri olarak taşınır;
 * shell yorumlaması yoktur, komut enjeksiyonu mümkün değildir.
 */
object SshdArguments {
    fun build(port: Int, passwordAuthEnabled: Boolean, listenLan: Boolean): List<String> {
        require(port in 1024..65_535) { "SSH port must be unprivileged" }
        return listOf(
            "-D",
            "-e",
            "-p", port.toString(),
            "-o", if (passwordAuthEnabled) "PasswordAuthentication=yes" else "PasswordAuthentication=no",
            "-o", "PubkeyAuthentication=yes",
            "-o", "KbdInteractiveAuthentication=no",
            "-o", "StrictModes=yes",
            "-o", "AllowTcpForwarding=no",
            "-o", "X11Forwarding=no",
            "-o", if (listenLan) "ListenAddress=0.0.0.0" else "ListenAddress=127.0.0.1",
        )
    }
}
