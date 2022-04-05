CRONET_AAR_DIR="./cronet"
GOST_BIN_DIR="./jni/arm64-v8a"

# check for arguments
if [ -z "$1" ]
  then
    echo "enter debug or release"
    exit 1
fi

# get cronet aar
cp "$CRONET_AAR_DIR/cronet-$1.aar" "cronet-$1.zip"

# get gost zip
wget --continue https://github.com/ginuerzh/gost/releases/download/v2.11.1/gost-linux-armv8-2.11.1.gz

# unzip gost binary and update name/properties
gzip -d gost-linux-armv8-2.11.1.gz
mkdir -p "$GOST_BIN_DIR/"
mv gost-linux-armv8-2.11.1 "$GOST_BIN_DIR/gost-linux-armv8.so"
chmod +x "$GOST_BIN_DIR/gost-linux-armv8.so"

# pack gost binary into cronet aar
zip -r "cronet-$1.zip" jni

# cleanup
mv -f "cronet-$1.zip" "$CRONET_AAR_DIR/cronet-$1.aar"
rm -f -r jni