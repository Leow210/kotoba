#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// The translated Kotoba core (dictionary search, books, comics, cards) behind a small Objective-C face for Swift.
@interface KotobaCore : NSObject

- (instancetype)initWithData:(NSString *)dataDir
                      assets:(NSString *)assetsDir
                      device:(NSString *)deviceName
                     onEvent:(void (^)(NSString *json))onEvent;

/// One request the interface makes: a route and its JSON body; the JSON reply ({"data":…} or {"error":…}).
- (NSString *)call:(NSString *)route body:(NSString *)body;

/// A page or resource by kind ("d", "comic", "book") and path: @[mime, NSData, entry flag or NSNull], or nil.
- (nullable NSArray *)fileKind:(NSString *)kind path:(NSString *)path;

/// Apple's text recognizer for comic text: image data and language in, JSON text out (nil and an error message when it fails).
- (void)setRecognizer:(NSString *_Nullable (^)(NSData *image, NSString *lang, NSString *_Nullable *_Nonnull error))recognizer;

/// Syncs with the chosen folder if there is one. Never throws: what went wrong (for the log), or nil.
- (nullable NSString *)syncQuietly;

@end

NS_ASSUME_NONNULL_END
