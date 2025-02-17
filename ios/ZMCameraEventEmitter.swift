//
//  ZMCameraEventEmitter.swift
//  Pods
//
//  Created by Arbab Ahmad on 17/02/25.
//

import Foundation
import React

@objc(ZMCameraEventEmitter)
class ZMCameraEventEmitter: RCTEventEmitter {
	
	// Singleton instance for shared use
	public static var shared: ZMCameraEventEmitter?
	
	override init() {
		super.init()
		ZMCameraEventEmitter.shared = self
	}
	
	// MARK: - Required Methods
	
	/// Required method to expose the module to React Native
	@objc
	override static func requiresMainQueueSetup() -> Bool {
		return true
	}
	
	/// List of events supported by this emitter
	override func supportedEvents() -> [String] {
		return ["onImageCaptured", "onLensChange"]
	}
	
	// MARK: - Emit Event
	
	/// Method to send an event to JavaScript
	func sendEventToReact(eventName: String, body: [String: Any]) {
		sendEvent(withName: eventName, body: body)
	}
}
