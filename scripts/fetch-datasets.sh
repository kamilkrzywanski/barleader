#!/bin/sh
# Downloads the public reading benchmarks that need no account, checks their SHA-256 and unpacks
# them where the snapshot tool reads them:
#
#   scripts/fetch-datasets.sh [dir]
#   mvn -q -Psnapshot test-compile exec:java -Dexec.args="target/snapshot.tsv --external <dir>"
#
# The directory defaults to $BARLEADER_DATASETS, else ~/.cache/barleader/datasets. Nothing goes
# into the repository: the licences differ, and the archives take about 1.7 GB, BarBeR 6 GB more.
#
#   kaist-deal      Do and Kim, KAIST: phone photos of EAN-13 and EAN-8 with the code in each file
#                   name, plus backgrounds without a barcode. CC BY 4.0. https://zenodo.org/records/13586402
#                   The authors' older real.zip is left out: it holds crops of the Arte-Lab photos.
#   zxing-blackbox  The test images of ZXing 3.5.4 with their expected texts. Apache 2.0.
#   boofcv-qr       BoofCV QR v3: hard QR codes in 16 categories, labelled with corners only.
#                   https://boofcv.org/index.php?title=Performance:QrCode
#   barber          BarBeR, Vezzali et al.: 8748 photos from 12 sets, every code with its type,
#                   polygon and text. It needs an account at https://ditto.ing.unimore.it/barber/,
#                   so it is only unpacked: BARBER_ZIP=<path to BarBeR_Dataset.zip> scripts/fetch-datasets.sh
#
# Zenodo serves one connection slowly, so a large file is fetched in parallel ranges.
set -eu

DEST=${1:-${BARLEADER_DATASETS:-$HOME/.cache/barleader/datasets}}
PARTS=${PARTS:-8}
ZENODO=https://zenodo.org/records/13586402/files
mkdir -p "$DEST/downloads"

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# part <url> <first> <last> <file>: bytes first..last into file, resuming after a dropped connection.
part() {
  tries=0
  while :; do
    have=0
    if [ -f "$4" ]; then
      have=$(wc -c < "$4" | tr -d ' ')
    fi
    want=$(( $3 - $2 + 1 ))
    if [ "$have" -eq "$want" ]; then
      return 0
    fi
    if [ "$have" -gt "$want" ] || [ "$tries" -ge 10 ]; then
      return 1
    fi
    tries=$(( tries + 1 ))
    curl -sSfL -r "$(( $2 + have ))-$3" "$1" >> "$4" || sleep 3
  done
}

# fetch <file> <url> <sha256>: downloads into $DEST/downloads unless a file with that hash is there.
# The parts of an interrupted download stay, and the next run resumes them.
fetch() {
  file=$DEST/downloads/$1
  if [ -f "$file" ] && [ "$(sha256 "$file")" = "$3" ]; then
    echo "$1: present"
    return
  fi
  echo "$1: downloading"
  parts=$file.parts
  mkdir -p "$parts"
  size=$(curl -sSIL "$2" | tr -d '\r' | awk 'tolower($1) == "content-length:" { n = $2 } END { print n }')
  if [ -n "$size" ] && [ "$size" -gt 33554432 ] && [ "$PARTS" -gt 1 ]; then
    chunk=$(( (size + PARTS - 1) / PARTS ))
    pids=
    i=0
    while [ "$i" -lt "$PARTS" ]; do
      start=$(( i * chunk ))
      end=$(( start + chunk - 1 ))
      if [ "$end" -ge "$size" ]; then
        end=$(( size - 1 ))
      fi
      part "$2" "$start" "$end" "$parts/$i" &
      pids="$pids $!"
      i=$(( i + 1 ))
    done
    failed=0
    for pid in $pids; do
      wait "$pid" || failed=1
    done
    if [ "$failed" -ne 0 ]; then
      echo "$1: a part failed to download" >&2
      exit 1
    fi
    i=0
    : > "$file.new"
    while [ "$i" -lt "$PARTS" ]; do
      cat "$parts/$i" >> "$file.new"
      i=$(( i + 1 ))
    done
  elif [ -n "$size" ]; then
    part "$2" 0 $(( size - 1 )) "$parts/0" || { echo "$1: download failed" >&2; exit 1; }
    mv "$parts/0" "$file.new"
  else
    curl -sSfL --retry 5 --retry-delay 3 -o "$file.new" "$2"
  fi
  rm -rf "$parts"
  actual=$(sha256 "$file.new")
  # FETCH_RECORD=1 accepts a file whose hash is not recorded yet, for adding a dataset.
  if [ -z "$3" ] && [ -n "${FETCH_RECORD:-}" ]; then
    echo "$1: sha256 $actual"
  elif [ "$actual" != "$3" ]; then
    echo "$1: SHA-256 is $actual, expected ${3:-(none recorded)}" >&2
    exit 1
  fi
  mv "$file.new" "$file"
}

# unpacked <dir> <archive>: true when <dir> already holds this exact archive, unpacked.
unpacked() {
  [ -f "$1/.archive.sha256" ] && [ "$(cat "$1/.archive.sha256")" = "$(sha256 "$2")" ]
}

unzip_into() {
  dir=$1
  archive=$DEST/downloads/$2
  if unpacked "$dir" "$archive"; then
    return
  fi
  rm -rf "$dir"
  mkdir -p "$dir"
  unzip -q -o "$archive" -d "$dir"
  sha256 "$archive" > "$dir/.archive.sha256"
}

# KAIST DEAL barcode set.
for name in barcode_bb e8_bb multi_bb background; do
  case $name in
    barcode_bb) sum=cc108ad25e91abc73be1dfc3df26cce5dcacd79f26695a7e522be9afd0d2f59a ;;
    e8_bb) sum=ffb413e5ff0dd5a1008f6706150b9945ba647ad8b9769cc5b9a586e40642da04 ;;
    multi_bb) sum=bbaa2f38e57483bbf3c38393bf3a90a32db650b8fdb9ea01fdc6ad8b9c47bb56 ;;
    background) sum=ef1801807b78f47469335d6c442bb78f7625d38c08809d4422e444ec79c5b795 ;;
  esac
  fetch "kaist-$name.zip" "$ZENODO/$name.zip?download=1" "$sum"
  unzip_into "$DEST/kaist-deal/$name" "kaist-$name.zip"
done

# ZXing 3.5.4 blackbox test images: only that directory of the source archive is kept.
fetch zxing-3.5.4.tar.gz https://codeload.github.com/zxing/zxing/tar.gz/refs/tags/zxing-3.5.4 \
  0eb2f54ad45ee19617dd0fb6ac19e4aa7022d6a6d0a8c95b4e85727bd67ee56c
archive=$DEST/downloads/zxing-3.5.4.tar.gz
if ! unpacked "$DEST/zxing-blackbox" "$archive"; then
  rm -rf "$DEST/zxing-blackbox" "$DEST/zxing-zxing-3.5.4"
  tar -xzf "$archive" -C "$DEST" zxing-zxing-3.5.4/core/src/test/resources/blackbox
  mv "$DEST/zxing-zxing-3.5.4/core/src/test/resources/blackbox" "$DEST/zxing-blackbox"
  rm -rf "$DEST/zxing-zxing-3.5.4"
  sha256 "$archive" > "$DEST/zxing-blackbox/.archive.sha256"
fi

# BoofCV QR v3.
fetch boofcv-qrcodes_v3.zip https://boofcv.org/notwiki/regression/fiducial/qrcodes_v3.zip \
  a136c676448e9bafbe5c2fe9487728b0da3c823aeace6bc74d1c7fb2f86fe7be
unzip_into "$DEST/boofcv-qr" boofcv-qrcodes_v3.zip

# BarBeR, from an archive downloaded with an account.
if [ -n "${BARBER_ZIP:-}" ]; then
  actual=$(sha256 "$BARBER_ZIP")
  if [ "$actual" != 92e0d43a0b56d30b96bf285e1fdd44940775796f922bcae673aefcb1d6d988e7 ]; then
    echo "$BARBER_ZIP: SHA-256 is $actual, not the BarBeR archive this was written for" >&2
    exit 1
  fi
  if [ ! -f "$DEST/barber/.archive.sha256" ] || [ "$(cat "$DEST/barber/.archive.sha256")" != "$actual" ]; then
    rm -rf "$DEST/barber"
    mkdir -p "$DEST/barber"
    unzip -q -o "$BARBER_ZIP" -d "$DEST/barber"
    echo "$actual" > "$DEST/barber/.archive.sha256"
  fi
  echo "barber: unpacked"
fi

echo "Datasets are in $DEST"
