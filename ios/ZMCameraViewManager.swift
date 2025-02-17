//
//  ZMCameraViewManager.swift
//  ZMCSample
//
//  Created by Arbab Ahmad on 07/02/25.
//

import React

@objc(ZMCameraViewManager)
class ZMCameraViewManager: RCTViewManager {
	
	override func view() -> UIView! {
		return ZMCCameraView()
	}
	
	@objc override static func requiresMainQueueSetup() -> Bool {
		return true
	}
}
