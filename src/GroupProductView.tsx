import React, { useEffect } from 'react';
import { View, StyleSheet, ViewStyle, NativeEventEmitter, NativeModules } from 'react-native';
import { ZMCameraView } from './CameraViewManager'; // Import the native component

// Access the native module for event handling
const { ZMCameraEventEmitter } = NativeModules;

// Define the types for the component props
interface GroupProductViewProps {
  apiToken: string;
  groupId: string;
  showPreview?: boolean; // Optional prop for showing preview
  showFrontCamera?: boolean;
  onImageCaptured?: (imageUri: string) => void; // Callback for when image is captured
  onLensChange?: (lensId: string) => void; // Callback for when lens changes
}

const GroupProductView: React.FC<GroupProductViewProps> = ({ 
  apiToken, 
  groupId,
  showFrontCamera = false,
  showPreview = true, // Default value is true for showing preview
  onImageCaptured,
  onLensChange,
}) => {

  useEffect(() => {
    const eventEmitter = new NativeEventEmitter(ZMCameraEventEmitter);

    // Listen for image captured event from the native module
    const imageCapturedListener = eventEmitter.addListener('onImageCaptured', (event: { imageUri: string }) => {
      if (onImageCaptured) {
        onImageCaptured(event.imageUri);
      }
    });

    // Listen for lens change event from the native module
    const lensChangeListener = eventEmitter.addListener('onLensChange', (event: { lensId: string }) => {
      if (onLensChange) {
        onLensChange(event.lensId);
      }
    });

    // Cleanup listeners when component unmounts
    return () => {
      imageCapturedListener.remove();
      lensChangeListener.remove();
    };
  }, [onImageCaptured, onLensChange]);

  return (
    <View style={styles.container}>
      <ZMCameraView
        style={styles.cameraView}
        singleLens={false} // Group mode: single lens disabled
        showFrontCamera={showFrontCamera}
        apiToken={apiToken}
        groupId={groupId}
        lensId="" // Empty lensId since this is for group mode
        showPreview={showPreview} // Pass the showPreview prop
      />
    </View>
  );
};

// Define styles using React Native's StyleSheet
const styles = StyleSheet.create<{
  container: ViewStyle;
  cameraView: ViewStyle;
}>({
  container: {
    flex: 1,
    backgroundColor: '#000', // Black background for better contrast with the camera view
  },
  cameraView: {
    flex: 1, // Ensures the camera view takes up the full available space
  },
});

export default GroupProductView;
