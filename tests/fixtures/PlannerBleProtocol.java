package com.faceclaw.app;
final class BleProtocol {
 static final class ImageTileOptions {}
 static final class ImageFragment {
  final int index;final byte[] data;final int logicalSize;
  ImageFragment(int index,byte[] data,int logicalSize){this.index=index;this.data=data;this.logicalSize=logicalSize;}
 }
}
