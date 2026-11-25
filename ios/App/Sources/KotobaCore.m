#import "KotobaCore.h"
#include "app/kotoba/reader/Core.h"
#include "java/nio/file/FileSystems.h"
#include "java/lang/Exception.h"
#include "IOSPrimitiveArray.h"
#include "IOSObjectArray.h"

@interface KotobaListener : NSObject <AppKotobaReaderCore_Listener>
@property(nonatomic, copy) void (^block)(NSString *);
@end
@implementation KotobaListener
- (void)eventWithNSString:(NSString *)json { if (self.block) self.block(json); }
@end

@interface KotobaRecognizer : NSObject <AppKotobaReaderCore_Recognizer>
@property(nonatomic, copy) NSString *(^block)(NSData *, NSString *, NSString **);
@end
@implementation KotobaRecognizer
- (NSString *)recognizeWithByteArray:(IOSByteArray *)image withNSString:(NSString *)lang {
  NSData *data = [NSData dataWithBytes:image->buffer_ length:image->size_];
  NSString *error = nil;
  NSString *json = self.block(data, lang, &error);
  if (!json) @throw create_JavaLangException_initWithNSString_(error ?: @"Text recognition failed.");
  return json;
}
@end

@implementation KotobaCore {
  AppKotobaReaderCore *_core;
  KotobaListener *_listener;
  KotobaRecognizer *_recognizer;
}

- (instancetype)initWithData:(NSString *)dataDir assets:(NSString *)assetsDir device:(NSString *)deviceName onEvent:(void (^)(NSString *))onEvent {
  self = [super init];
  if (!self) return nil;
  (void)JavaNioFileFileSystems_class_();
  _listener = [KotobaListener new];
  _listener.block = onEvent;
  _core = [[AppKotobaReaderCore alloc] initWithNSString:dataDir withNSString:assetsDir withNSString:deviceName withAppKotobaReaderCore_Listener:_listener];
  return self;
}

- (NSString *)call:(NSString *)route body:(NSString *)body {
  @try { return [_core callWithNSString:route withNSString:body]; }
  @catch (id e) { return @"{\"error\":\"Something went wrong inside Kotoba.\"}"; }
}

- (NSArray *)fileKind:(NSString *)kind path:(NSString *)path {
  @try {
    IOSObjectArray *r = [_core fileWithNSString:kind withNSString:path];
    if (!r) return nil;
    IOSByteArray *bytes = [r objectAtIndex:1];
    NSData *data = [NSData dataWithBytes:bytes->buffer_ length:bytes->size_];
    id flag = [r objectAtIndex:2];
    return @[ [r objectAtIndex:0], data, flag ?: [NSNull null] ];
  } @catch (id e) { return nil; }
}

- (void)setRecognizer:(NSString *(^)(NSData *, NSString *, NSString **))recognizer {
  _recognizer = [KotobaRecognizer new];
  _recognizer.block = recognizer;
  [_core setRecognizerWithAppKotobaReaderCore_Recognizer:_recognizer];
}

- (NSString *)syncQuietly { @try { return [_core syncQuietly]; } @catch (id e) { return [e description]; } }

@end
