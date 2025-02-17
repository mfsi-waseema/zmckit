//
//  ZMCCameraViewManager.m
//  ZMCSample
//
//  Created by Arbab Ahmad on 17/01/25.
//

#import <UIKit/UIKit.h>
#import <React/RCTViewManager.h>
#import <Foundation/Foundation.h>

@interface RCT_EXTERN_MODULE(ZMCameraViewManager, RCTViewManager)
RCT_EXPORT_VIEW_PROPERTY(apiToken, NSString)
RCT_EXPORT_VIEW_PROPERTY(lensId, NSString)
RCT_EXPORT_VIEW_PROPERTY(singleLens, BOOL)
RCT_EXPORT_VIEW_PROPERTY(groupId, NSString)
RCT_EXPORT_VIEW_PROPERTY(showFrontCamera, BOOL)
RCT_EXPORT_VIEW_PROPERTY(showPreview, BOOL)

@end
