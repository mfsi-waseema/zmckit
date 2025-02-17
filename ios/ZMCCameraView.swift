//
//  CameraView.swift
//  ZMCSample
//
//  Created by Arbab Ahmad on 16/01/25.
//

import Foundation
import UIKit
import React

@objc(ZMCCameraView)
class ZMCCameraView: UIView {
	
	private var cameraLayout: UIView?
	
	override init(frame: CGRect) {
		super.init(frame: frame)
	}
	
	required init?(coder: NSCoder) {
		fatalError("init(coder:) has not been implemented")
	}
		
	@objc var apiToken: String = "" {
		didSet { updateCameraView() }
	}
	
	@objc var lensId: String = "" {
		didSet { updateCameraView() }
	}
	
	@objc var singleLens: Bool = false {
		didSet { }
	}
	
	@objc var groupId: String = "" {
		didSet { updateCameraView() }
	}
	
	@objc var showFrontCamera: Bool = false {
		didSet {
			
		}
	}
	
	@objc var showPreview: Bool = true {
		didSet {
			
		}
	}
	
	// MARK: - Camera Initialization
	private func updateCameraView() {
		DispatchQueue.main.async { [weak self] in
			guard let self = self else { return }
			
			if self.singleLens {
				if self.apiToken.isEmpty || self.lensId.isEmpty || self.groupId.isEmpty {
					print("Missing required parameters for single lens mode.")
					return
				}
			}
			
			if self.apiToken.isEmpty || self.groupId.isEmpty {
				print("Missing required parameters for multi-lens mode.")
				return
			}
			
			// Prevent setup if already set up
			if isCameraLayoutSetup {
				return
			}
			
			// Initialize ZMCKit once
			ZMCKit.initialize { initialized in
				if initialized {
					print("ZMCKit successfully initialized.")
				} else {
					print("ZMCKit failed to initialize.")
				}
			}
			
			ZMCKit.onLensChange { lensId in
				if let lensId = lensId {
					self.sendEvent(withName: "onLensChange", body: ["lensId": lensId])
				}
			}
			
			self.setupCameraLayout()
		}
	}
	
	private var isCameraLayoutSetup: Bool = false
	
	private func setupCameraLayout() {
		
		// Cleanup previous layout if it exists
		cameraLayout?.removeFromSuperview()
		cameraLayout = nil
		
		// Choose camera layout based on singleLens flag
		cameraLayout = singleLens ? createSingleLensCameraLayout() : createGroupLensCameraLayout()
		
		// Add the layout to the view using frames
		if let cameraLayout = cameraLayout {
			addSubview(cameraLayout)
			
			// Set the frame of the cameraLayout to match the bounds of the parent view
			cameraLayout.frame = self.frame
			
			cameraLayout.translatesAutoresizingMaskIntoConstraints = false
			NSLayoutConstraint.activate([
				cameraLayout.topAnchor.constraint(equalTo: self.topAnchor),
				cameraLayout.bottomAnchor.constraint(equalTo: self.bottomAnchor),
				cameraLayout.leadingAnchor.constraint(equalTo: self.leadingAnchor),
				cameraLayout.trailingAnchor.constraint(equalTo: self.trailingAnchor)
			])
			
			// Mark the layout as set up
			isCameraLayoutSetup = true
		}
	}
	
	private func createSingleLensCameraLayout() -> UIView {
		let singleLensView = ZMCKit.createSingleProductView(
			snapAPIToken: self.apiToken,
			partnerGroupId: self.groupId,
			lensId: self.lensId,
			cameraPosition: showFrontCamera ? .front : .back
		)
		singleLensView.delegate = self
		return singleLensView
	}
	
	private func createGroupLensCameraLayout() -> UIView {
		let multiLensView = ZMCKit.createMultiProductView(
			snapAPIToken: self.apiToken,
			partnerGroupId: self.groupId,
			cameraPosition: showFrontCamera ? .front : .back
		)
		multiLensView.delegate = self
		return multiLensView
	}
	
	deinit {
		cameraLayout?.removeFromSuperview()
	}
}

// MARK: - ZMCameraDelegate Implementation
extension ZMCCameraView: ZMCameraDelegate {
	
	func cameraDidCapture(image: UIImage?) {
		print("Camera captured an image.")
		
		// Ensure the image is not nil
		guard let image = image else {
			print("No image captured.")
			return
		}
		
		// Convert the image to JPEG data (you could use PNG or another format if needed)
		guard let imageData = image.jpegData(compressionQuality: 1.0) else {
			print("Failed to convert image to data.")
			return
		}
		
		// Save the image data to a temporary file
		let tempDirectory = FileManager.default.temporaryDirectory
		let tempURL = tempDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
		
		do {
			// Write the image data to the file
			try imageData.write(to: tempURL)
			print("Image saved to \(tempURL.path)")
			
			// Send the event with the image URI to React Native
			sendEvent(withName: "onImageCaptured", body: ["imageUri": tempURL.absoluteString])
			
		} catch {
			print("Failed to save image: \(error)")
		}
	}
	
	func shouldShowDefaultPreview() -> Bool {
		return self.showPreview
	}
	
	func willShowPreview(image: UIImage?) {
		print("Will show preview for captured image.")
	}
}

extension ZMCCameraView {
	
	// Method to send the event to JavaScript (React Native)
	private func sendEvent(withName name: String, body: [String: Any]) {
		ZMCameraEventEmitter.shared?.sendEventToReact(eventName: name, body: body)
	}
}
