import React, { useEffect } from 'react';
import { View, StyleSheet, NativeEventEmitter, NativeModules } from 'react-native';
import { ZMCameraView } from './CameraViewManager'; // Import your ZMCameraView native component

interface SingleProductViewProps {
  apiToken: string;
  lensId: string;
  groupId: string;
  showFrontCamera?: boolean;
  showPreview?: boolean;
  onImageCaptured?: (imageUri: string) => void;
}

const SingleProductView: React.FC<SingleProductViewProps> = ({
  apiToken,
  lensId,
  groupId,
  showFrontCamera = false,
  showPreview = true,
  onImageCaptured,
}) => {

  // Access NativeModules
  const { ZMCameraEventEmitter } = NativeModules;

  useEffect(() => {
    // Initialize the NativeEventEmitter for the ZMCameraEventEmitter
    const eventEmitter = new NativeEventEmitter(ZMCameraEventEmitter);

    // Listen for image captured event from native code
    const imageCapturedListener = eventEmitter.addListener('onImageCaptured', (event: { imageUri: string }) => {
      if (onImageCaptured) {
        onImageCaptured(event.imageUri);
      }
    });

    // Cleanup listeners on component unmount
    return () => {
      imageCapturedListener.remove();
    };
  }, [onImageCaptured]);

  return (
    <View style={styles.container}>
      <ZMCameraView
        style={styles.cameraView}
        singleLens={true}
        showFrontCamera={showFrontCamera}
        apiToken={apiToken}
        lensId={lensId}
        groupId={groupId}
        showPreview={showPreview}
      />
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  cameraView: {
    flex: 1,
  },
});

export default SingleProductView;