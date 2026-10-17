# Hammerhead Karoo Extension API
-keep class io.hammerhead.karooext.** { *; }
-keep interface io.hammerhead.karooext.** { *; }

# Application components (Services, Receivers, App)
-keep class io.github.JaJaJim.kmagicwheeltopblip.service.KarooEdsChargeExtensionService { *; }
-keep class io.github.JaJaJim.kmagicwheeltopblip.AdbTestReceiver { *; }
-keep class io.github.JaJaJim.kmagicwheeltopblip.KarooEdsChargeApp { *; }

# DataStore & Preferences
-keep class androidx.datastore.** { *; }
