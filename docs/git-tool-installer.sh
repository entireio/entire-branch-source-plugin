set -eu
# git-remote-entire for the "entire" git tool. Pinned release, sha256 checked.
V=0.13.4
case "$(uname -m)" in
  x86_64|amd64) A=amd64; SUM=9d8e764659611edbadec127df825a6e46850407a727eb23aae3eadb738e320f1 ;;
  aarch64|arm64) A=arm64; SUM=54568cd4e7dc9c51f53c3fc26f4b9096d4acba46fea51b5654de29088dd9d086 ;;
  *) echo "unsupported arch $(uname -m)" >&2; exit 1 ;;
esac
REAL_GIT=$(command -v git)
curl -fsSL -o gre.tgz "https://install.entire.com/v$V/git-remote-entire-linux-$A.tar.gz"
echo "$SUM  gre.tgz" | sha256sum -c -
tar -xzf gre.tgz git-remote-entire
rm gre.tgz
printf '#!/bin/sh\nPATH="%s:$PATH" exec "%s" "$@"\n' "$PWD" "$REAL_GIT" > git
chmod 755 git git-remote-entire
