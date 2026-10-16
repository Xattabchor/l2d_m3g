# 3D2 OBJ Converter for J2ME

A small MIDP 2.0 / CLDC 1.1 application that runs on the phone and converts static Wavefront `.obj` files into the `.3d2` format used by L2D M3G.

## Supported OBJ data

- positions (`v`), texture coordinates (`vt`), normals (`vn`) and faces (`f`)
- positive and negative OBJ indices
- triangles, quads and larger polygons (larger faces are fan-triangulated)
- texture V coordinates are flipped to match the desktop converter/import pipeline

Materials, textures, animation/skeletons, lines and points are not imported. The OBJ's associated image can still be used separately by the game. The output contains a single mesh/submesh and is written next to the source OBJ with the same base name and a `.3d2` extension; an existing output is replaced.

The in-app file browser uses JSR-75 FileConnection. The device must support JSR-75 and grant file read/write permission. To keep memory use suitable for older phones, the converter limits a model to 4,096 output vertices and 8,192 triangles.

## Build

Requires JDK 8, CLDC 1.1 and MIDP 2.0 API JARs, the JSR-75 API JAR, and ProGuard with `-microedition` support. Provide their locations as environment variables:

```sh
JAVA_HOME=/path/to/jdk8 \
J2ME_API_DIR=/path/to/j2me/api-jars \
PROGUARD_JAR=/path/to/proguard.jar \
./build.sh
```

`J2ME_API_DIR` must contain `cldcapi11.jar`, `midpapi20.jar` and `jsr75.jar`. The build outputs are `dist/ModelConverterJ2ME.jar` and `dist/ModelConverterJ2ME.jad`; `build/preverified.jar` contains the preverified application classes.
