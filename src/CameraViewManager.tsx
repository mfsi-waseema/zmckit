// CameraView.d.ts
import {requireNativeComponent, ViewStyle} from 'react-native';

type CameraViewProps = {
    style?: ViewStyle;
    singleLens:boolean,
    apiToken: string;
    lensId: string;
    groupId: string;
    showFrontCamera: boolean,
    showPreview:boolean
};

export const ZMCameraView = requireNativeComponent<CameraViewProps>('ZMCameraView');
