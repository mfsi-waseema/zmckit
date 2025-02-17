import AVFoundation

public enum CameraPermissionStatus {
    case authorized
    case denied
    case notDetermined
    case restricted
}

public class CameraPermissionHandler {
    public static func checkCameraPermission(completion: @escaping (CameraPermissionStatus) -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            completion(.authorized)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    completion(granted ? .authorized : .denied)
                }
            }
        case .denied:
            completion(.denied)
        case .restricted:
            completion(.restricted)
        @unknown default:
            completion(.denied)
        }
    }
} 
