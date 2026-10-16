#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAVA_HOME=${JAVA_HOME:-}
J2ME_API_DIR=${J2ME_API_DIR:-}
PROGUARD_JAR=${PROGUARD_JAR:-}

if [ -z "$JAVA_HOME" ]; then
    JAVAC=$(command -v javac || true)
    JAVA=$(command -v java || true)
    JAR=$(command -v jar || true)
else
    JAVAC="$JAVA_HOME/bin/javac"
    JAVA="$JAVA_HOME/bin/java"
    JAR="$JAVA_HOME/bin/jar"
fi

if [ -z "$JAVAC" ] || [ -z "$JAVA" ] || [ -z "$JAR" ]; then
    echo "Set JAVA_HOME to a JDK 8 installation." >&2
    exit 1
fi
if [ -z "$J2ME_API_DIR" ]; then
    echo "Set J2ME_API_DIR to a folder containing cldcapi11.jar, midpapi20.jar and jsr75.jar." >&2
    exit 1
fi
if [ -z "$PROGUARD_JAR" ]; then
    echo "Set PROGUARD_JAR to a ProGuard CLI JAR that supports -microedition." >&2
    exit 1
fi

CLDC="$J2ME_API_DIR/cldcapi11.jar"
MIDP="$J2ME_API_DIR/midpapi20.jar"
JSR75="$J2ME_API_DIR/jsr75.jar"
for api in "$CLDC" "$MIDP" "$JSR75"; do
    if [ ! -f "$api" ]; then echo "Missing Java ME API: $api" >&2; exit 1; fi
done
if [ ! -f "$PROGUARD_JAR" ]; then echo "Missing ProGuard JAR: $PROGUARD_JAR" >&2; exit 1; fi

BUILD="$ROOT/build"
DIST="$ROOT/dist"
rm -rf "$BUILD" "$DIST"
mkdir -p "$BUILD/classes" "$DIST"
find "$ROOT/src" -name '*.java' > "$BUILD/sources.txt"
BOOT="$CLDC:$MIDP:$JSR75"
"$JAVAC" -encoding UTF-8 -source 1.3 -target 1.1 \
    -bootclasspath "$BOOT" -classpath "$BOOT" \
    -d "$BUILD/classes" @"$BUILD/sources.txt"

cat > "$BUILD/proguard.pro" <<EOF
-injars $BUILD/classes
-outjars $BUILD/preverified.jar
-libraryjars $CLDC
-libraryjars $MIDP
-libraryjars $JSR75
-dontshrink
-dontoptimize
-dontobfuscate
-dontwarn
-ignorewarnings
-microedition
EOF
"$JAVA" -jar "$PROGUARD_JAR" @"$BUILD/proguard.pro"

cat > "$BUILD/MANIFEST.MF" <<'EOF'
Manifest-Version: 1.0
MIDlet-1: 3D2 OBJ Converter,,rmn20.j2me.ConverterMIDlet
MIDlet-Name: 3D2 OBJ Converter
MIDlet-Vendor: L2D M3G
MIDlet-Version: 1.0.0
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.0
MIDlet-Permissions-Opt: javax.microedition.io.Connector.file.read, javax.microedition.io.Connector.file.write

EOF
mkdir -p "$BUILD/preverified-classes"
(cd "$BUILD/preverified-classes" && "$JAR" xf "$BUILD/preverified.jar")
"$JAR" cfm "$DIST/ModelConverterJ2ME.jar" "$BUILD/MANIFEST.MF" -C "$BUILD/preverified-classes" .

SIZE=$(wc -c < "$DIST/ModelConverterJ2ME.jar" | tr -d ' ')
cat > "$DIST/ModelConverterJ2ME.jad" <<EOF
MIDlet-1: 3D2 OBJ Converter,,rmn20.j2me.ConverterMIDlet
MIDlet-Name: 3D2 OBJ Converter
MIDlet-Vendor: L2D M3G
MIDlet-Version: 1.0.0
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.0
MIDlet-Permissions-Opt: javax.microedition.io.Connector.file.read, javax.microedition.io.Connector.file.write
MIDlet-Jar-URL: ModelConverterJ2ME.jar
MIDlet-Jar-Size: $SIZE
EOF

echo "Built $DIST/ModelConverterJ2ME.jar ($SIZE bytes)"
echo "JAD: $DIST/ModelConverterJ2ME.jad"
echo "Preverified classes: $BUILD/preverified.jar"
