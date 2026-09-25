#!/data/data/com.termux/files/usr/bin/bash
# DNA 解包助手 — APK 构建脚本(无 Gradle, aapt2 + javac + d8 + apksigner)
set -e
cd "$(dirname "$0")"

export PATH="$PREFIX/bin:$PATH"
ANDROID_JAR="../build/tools/platform-34/android-34/android.jar"
OUT="build/apk"
GEN="build/gen"
CLS="build/classes-apk"
DEX="build/dex"
KSTORE="build/dna.keystore"
KSPASS="dna123456"
NATIVELIB="build/nativelib"

rm -rf "$OUT" "$GEN" "$CLS" "$DEX" "$NATIVELIB"
mkdir -p "$OUT" "$GEN" "$CLS" "$DEX"

echo "== [1/7] aapt2 compile resources =="
aapt2 compile --dir res -o "$OUT/res.zip"

MANIFEST="AndroidManifest.xml"
if [ -n "$DEBUG_BUILD" ]; then
    sed 's|<application|<application android:debuggable="true"|' AndroidManifest.xml > "$OUT/AndroidManifest.debug.xml"
    MANIFEST="$OUT/AndroidManifest.debug.xml"
fi

echo "== [2/7] aapt2 link =="
aapt2 link -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$MANIFEST" \
    --java "$GEN" \
    --min-sdk-version 21 --target-sdk-version 27 \
    -A assets \
    "$OUT/res.zip"

echo "== [3/7] javac =="
find "$GEN" -name "*.java" > "$OUT/gen-srcs.txt"
find java -name "*.java" > "$OUT/app-srcs.txt"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
    -classpath "$ANDROID_JAR" \
    -d "$CLS" \
    @"$OUT/gen-srcs.txt" @"$OUT/app-srcs.txt"

echo "== [4/7] d8 dex =="
find "$CLS" -name "*.class" > "$OUT/classes.txt"
d8 --release --lib "$ANDROID_JAR" --min-api 26 \
    --output "$DEX" @"$OUT/classes.txt"

echo "== [5/7] build native libs (C liquid glass) =="
if [ -d csrc ] && [ -f csrc/liquidglass.c ]; then
    mkdir -p "$NATIVELIB/arm64-v8a"
    clang -O3 -ffast-math -std=c99 -fPIC -shared -DANDROID \
        -Icsrc csrc/liquidglass.c csrc/liquidglass_jni.c \
        -o "$NATIVELIB/arm64-v8a/libliquidglass_jni.so" -lm
    echo "  原生库 arm64-v8a: $(stat -c%s "$NATIVELIB/arm64-v8a/libliquidglass_jni.so") 字节"
    # 如需 32 位可再编一份(这里保持精简)
else
    echo "  跳过(未找到 csrc/liquidglass.c)"
fi

echo "== [6/7] pack dex + native libs into apk =="
python3 - "$OUT" "$DEX" "$NATIVELIB" <<'PY'
import sys, shutil, zipfile, os
out, dex, nat = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copy(out + '/base.apk', out + '/unsigned.apk')
with zipfile.ZipFile(out + '/unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex + '/classes.dex', 'classes.dex')
    if os.path.isdir(nat):
        for root, dirs, files in os.walk(nat):
            for n in files:
                p = os.path.join(root, n)
                arc = 'lib/' + os.path.relpath(p, nat).replace(os.sep, '/')
                z.write(p, arc)
                print('  + ' + arc)
PY

echo "== [7/7] sign =="
if [ ! -f "$KSTORE" ]; then
    keytool -genkeypair -keystore "$KSTORE" -alias dna -keyalg RSA -keysize 2048 \
        -validity 10950 -storepass "$KSPASS" -keypass "$KSPASS" \
        -dname "CN=DNA Unpacker, OU=DNA, O=zhiyu, C=CN" 2>/dev/null
fi
apksigner sign --ks "$KSTORE" --ks-pass "pass:$KSPASS" \
    --out "$OUT/DNA-No-ROOT-V3.0.apk" "$OUT/unsigned.apk"

echo ""
echo "APK: $OUT/DNA-No-ROOT-V3.0.apk"
ls -lh "$OUT/DNA-No-ROOT-V3.0.apk"
