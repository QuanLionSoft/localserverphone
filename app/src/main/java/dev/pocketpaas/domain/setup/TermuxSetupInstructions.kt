package dev.pocketpaas.domain.setup

/** Reviewed local setup; no downloaded script, root access, or app secrets are involved. */
object TermuxSetupInstructions {
    /** A single paste executes in its own shell, leaving the user's terminal open on failure. */
    val command: String
        get() = "sh -c '" + script.replace("'", "'\\''") + "'"

    private val script = """
        set -eu
        setup_result() {
            result=${'$'}?
            if [ "${'$'}result" -ne 0 ]; then
                printf '\nKurulum tamamlanamadı. Yukarıdaki hatayı kontrol edip aynı komutu yeniden çalıştırın.\n' >&2
            fi
        }
        trap setup_result 0
        if ! command -v pkg >/dev/null 2>&1; then
            printf 'Bu komutu Termux uygulamasının terminaline yapıştırın.\n' >&2
            exit 1
        fi
        printf 'Kurulu tüm Termux paketleri güncelleniyor ve sunucu araçları kuruluyor. İnternet bağlantısını açık tutun.\n'
        export DEBIAN_FRONTEND=noninteractive
        pkg update -y
        pkg upgrade -y
        pkg install -y python nginx nodejs cloudflared
        for binary in python nginx node npm cloudflared termux-reload-settings; do
            if ! command -v "${'$'}binary" >/dev/null 2>&1; then
                printf 'Gerekli komut bulunamadı: %s\n' "${'$'}binary" >&2
                exit 1
            fi
        done
        printf 'Sunucu araçlarının çalışabildiği kontrol ediliyor.\n'
        python --version
        nginx -v
        node --version
        npm --version
        cloudflared --version
        python - <<'POCKETPAAS_SETTINGS'
        import os
        import re
        import stat
        import tempfile
        from pathlib import Path

        WHITESPACE = ' \t\f'
        KEY = 'allow-external-apps'

        def property_key(logical):
            line = logical.lstrip(WHITESPACE)
            if not line or line[0] in '#!':
                return None
            result = []
            index = 0
            while index < len(line):
                char = line[index]
                index += 1
                if char in WHITESPACE + '=:':
                    break
                if char == '\\':
                    if index >= len(line):
                        break
                    char = line[index]
                    index += 1
                    if char == 'u':
                        digits = line[index:index + 4]
                        if len(digits) != 4 or any(c not in '0123456789abcdefABCDEF' for c in digits):
                            raise ValueError('termux.properties içinde geçersiz Unicode kaçış dizisi var.')
                        char = chr(int(digits, 16))
                        index += 4
                    else:
                        char = {'t': '\t', 'r': '\r', 'n': '\n', 'f': '\f'}.get(char, char)
                result.append(char)
            return ''.join(result)

        def without_external_app_property(text):
            output = []
            physical = []
            logical = ''
            for match in re.finditer(r'[^\r\n]*(?:\r\n|\r|\n|${'$'})', text):
                raw = match.group(0)
                if not raw:
                    continue
                body = raw.rstrip('\r\n')
                if not physical and (not body.lstrip(WHITESPACE) or body.lstrip(WHITESPACE).startswith(('#', '!'))):
                    output.append(raw)
                    continue
                physical.append(raw)
                logical += body if len(physical) == 1 else body.lstrip(WHITESPACE)
                trailing_slashes = len(body) - len(body.rstrip('\\'))
                if trailing_slashes % 2:
                    logical = logical[:-1]
                    continue
                if property_key(logical) != KEY:
                    output.extend(physical)
                physical = []
                logical = ''
            if physical and property_key(logical) != KEY:
                output.extend(physical)
            newline = '\r\n' if '\r\n' in text else '\n'
            preserved = ''.join(output)
            if preserved and not preserved.endswith(('\n', '\r')):
                preserved += newline
            if physical and property_key(logical) != KEY:
                preserved += newline
            return preserved + KEY + ' = true' + newline

        directory = Path.home() / '.termux'
        try:
            directory.mkdir(mode=0o700)
        except FileExistsError:
            pass
        directory_info = directory.lstat()
        if stat.S_ISLNK(directory_info.st_mode) or not stat.S_ISDIR(directory_info.st_mode):
            raise ValueError('.termux normal bir klasör olmalı; sembolik bağlantı kabul edilmez.')
        target = directory / 'termux.properties'
        original_info = None
        try:
            original_info = target.lstat()
        except FileNotFoundError:
            pass
        if original_info is not None and not stat.S_ISREG(original_info.st_mode):
            raise ValueError('termux.properties normal bir dosya olmalı; sembolik bağlantı kabul edilmez.')
        if original_info is not None and original_info.st_size > 1024 * 1024:
            raise ValueError('termux.properties güvenli düzenleme boyutunu aşıyor (1 MiB).')
        if original_info is None:
            original = b''
        else:
            flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_BINARY', 0)
            with os.fdopen(os.open(target, flags), 'rb') as source:
                opened_info = os.fstat(source.fileno())
                if (opened_info.st_dev, opened_info.st_ino) != (original_info.st_dev, original_info.st_ino):
                    raise ValueError('Ayar dosyası kurulum sırasında değişti; tekrar deneyin.')
                original = source.read(1024 * 1024 + 1)
            if len(original) > 1024 * 1024:
                raise ValueError('termux.properties güvenli düzenleme boyutunu aşıyor (1 MiB).')
        updated = without_external_app_property(original.decode('utf-8')).encode('utf-8')
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode='wb', dir=directory, prefix='.pocketpaas-setup-', delete=False) as output:
                temporary = Path(output.name)
                output.write(updated)
                output.flush()
                os.fsync(output.fileno())
            os.chmod(temporary, stat.S_IMODE(original_info.st_mode) if original_info is not None else 0o600)
            try:
                current_info = target.lstat()
            except FileNotFoundError:
                current_info = None
            if original_info is None:
                if current_info is not None:
                    raise ValueError('Ayar dosyası kurulum sırasında oluşturuldu; tekrar deneyin.')
            elif current_info is None or (
                current_info.st_dev, current_info.st_ino, current_info.st_size, current_info.st_mtime_ns
            ) != (
                original_info.st_dev, original_info.st_ino, original_info.st_size, original_info.st_mtime_ns
            ):
                raise ValueError('Ayar dosyası kurulum sırasında değişti; tekrar deneyin.')
            os.replace(temporary, target)
            temporary = None
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
        POCKETPAAS_SETTINGS
        termux-reload-settings
        printf '\nKurulum tamamlandı. Pocket PaaS uygulamasına dönün ve 4. adımda kontrolü başlatın.\n'
    """.trimIndent()
}
