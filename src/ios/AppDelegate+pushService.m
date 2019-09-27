

#import "AppDelegate.h"
#import <UserNotifications/UserNotifications.h>
#import "Constants.h"
#import "MainViewController.h"

extern NSString *token;
NSString *page;
@implementation AppDelegate(pushService)

- (BOOL)application:(UIApplication*)application didFinishLaunchingWithOptions:(NSDictionary*)launchOptions
{
    if ([[UIDevice currentDevice].systemVersion floatValue] >= 10.0) {
        //iOS10特有
        UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
        // 必须写代理，不然无法监听通知的接收与点击
        center.delegate = self;
        [center requestAuthorizationWithOptions:(UNAuthorizationOptionAlert | UNAuthorizationOptionBadge | UNAuthorizationOptionSound) completionHandler:^(BOOL granted, NSError * _Nullable error) {
            if (granted) {
                // 点击允许
                NSLog(@"注册成功");
                [center getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings * _Nonnull settings) {
                    NSLog(@"%@", settings);
                }];
            } else {
                // 点击不允许
                NSLog(@"注册失败");
            }
        }];
    }else if ([[UIDevice currentDevice].systemVersion floatValue] >8.0){
        //iOS8 - iOS10
        [application registerUserNotificationSettings:[UIUserNotificationSettings settingsForTypes:UIUserNotificationTypeAlert | UIUserNotificationTypeSound | UIUserNotificationTypeBadge categories:nil]];
        
    }else if ([[UIDevice currentDevice].systemVersion floatValue] < 8.0) {
        //iOS8系统以下
        [application registerForRemoteNotificationTypes:UIRemoteNotificationTypeBadge | UIRemoteNotificationTypeAlert | UIRemoteNotificationTypeSound];
    }
    // 注册获得device Token
    [[UIApplication sharedApplication] registerForRemoteNotifications];
    self.viewController = [[MainViewController alloc] init];
    return [super application:application didFinishLaunchingWithOptions:launchOptions];
}
// 获得Device Token
- (void)application:(UIApplication *)application
didRegisterForRemoteNotificationsWithDeviceToken:(NSData *)deviceToken {
    if ([[UIDevice currentDevice].systemVersion floatValue] >= 13.0) {
        NSMutableString *deviceTokenString = [NSMutableString string];
        const char *bytes = deviceToken.bytes;
        NSInteger count = deviceToken.length;
        for (int i = 0; i < count; i++) {
            [deviceTokenString appendFormat:@"%02x", bytes[i]&0x000000FF];
        }
        token=deviceTokenString;
    }else {
        token=[NSString stringWithFormat:@"%@", deviceToken];
    }
    NSLog(@"%@", [NSString stringWithFormat:@"Device Token: %@", deviceToken]);
}
// 获得Device Token失败
- (void)application:(UIApplication *)application
didFailToRegisterForRemoteNotificationsWithError:(NSError *)error {
    token=@"";
    NSLog(@"did Fail To Register For Remote Notifications With Error: %@", error);
}
// iOS 10收到通知
- (void)userNotificationCenter:(UNUserNotificationCenter *)center willPresentNotification:(UNNotification *)notification withCompletionHandler:(void (^)(UNNotificationPresentationOptions options))completionHandler{
    NSDictionary * userInfo = notification.request.content.userInfo;
    UNNotificationRequest *request = notification.request; // 收到推送的请求
    UNNotificationContent *content = request.content; // 收到推送的消息内容
    NSNumber *badge = content.badge;  // 推送消息的角标
    NSString *body = content.body;    // 推送消息体
    UNNotificationSound *sound = content.sound;  // 推送消息的声音
    NSString *subtitle = content.subtitle;  // 推送消息的副标题
    NSString *title = content.title;  // 推送消息的标题
    
    if([notification.request.trigger isKindOfClass:[UNPushNotificationTrigger class]]) {
       
        
    }
    else {
        // 判断为本地通知
        NSLog(@"iOS10 前台收到本地通知:{\\\\nbody:%@，\\\\ntitle:%@,\\\\nsubtitle:%@,\\\\nbadge：%@，\\\\nsound：%@，\\\\nuserInfo：%@\\\\n}",body,title,subtitle,badge,sound,userInfo);
    }
    completionHandler(UNNotificationPresentationOptionBadge|UNNotificationPresentationOptionSound|UNNotificationPresentationOptionAlert); // 需要执行这个方法，选择是否提醒用户，有Badge、Sound、Alert三种类型可以设置
}
- (void)application:(UIApplication *)application
didReceiveRemoteNotification:(NSDictionary *)userInfo {
    
}

- (void)application:(UIApplication *)application
didReceiveRemoteNotification:(NSDictionary *)userInfo
fetchCompletionHandler:
(void (^)(UIBackgroundFetchResult))completionHandler {
    
    NSLog(@"userInfo == %@",userInfo);
    NSString *message = [[userInfo objectForKey:@"aps"]objectForKey:@"alert"];
    page = [userInfo objectForKey:@"page"];
    UIAlertView *alert = [[UIAlertView alloc] initWithTitle:@"提示" message:message delegate:self cancelButtonTitle:@"取消" otherButtonTitles:@"查看", nil];
    NSLog(@"buttonTitleAtIndex1:%@",[alert buttonTitleAtIndex:1]);
    [alert show];
   
  
}
-(void)alertView:(UIAlertView *)alertView clickedButtonAtIndex:(NSInteger)buttonIndex
{
    if(buttonIndex==1){
        NSString *jsStr = [NSString stringWithFormat:@"Router.go('%@')",page];
        [self.viewController.commandDelegate evalJs:jsStr];
    }
}

- (void)application:(UIApplication*)applicationdidRegisterForRemoteNotificationsWithDeviceToken:(NSData*)deviceToken{
    NSLog(@"deviceToken: %@", deviceToken);
}


@end
