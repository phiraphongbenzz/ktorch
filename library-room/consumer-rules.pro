# Found through java.util.ServiceLoader at runtime; keep the class and its no-arg constructor.
-keep class com.phiraphongbenzz.ktorch.internal.room.RoomTransactionPersistence { public <init>(); }
-keepnames class com.phiraphongbenzz.ktorch.internal.spi.TransactionPersistence
