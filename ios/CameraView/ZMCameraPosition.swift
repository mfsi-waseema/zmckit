//
//  ZMCameraPosition.swift
//  ZMCKit
//
//  Created by Can Kocoglu on 23.01.2025.
//

public enum ZMCameraPosition {
    case front
    case back
    
    internal var avPosition: AVCaptureDevice.Position {
        switch self {
        case .front:
            return .front
        case .back:
            return .back
        }
    }
}
