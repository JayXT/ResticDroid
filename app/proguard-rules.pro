# WorkManager instantiates workers reflectively, by the class name it stored
# when the work was enqueued.
-keep class io.github.resticdroid.work.BackupWorker { <init>(...); }
-keep class io.github.resticdroid.work.PruneWorker { <init>(...); }

-dontwarn org.json.**

# R8 full mode does not count WorkManager's `instanceof Configuration.Provider`
# check as a use of the interface, and strips it - after which the app dies on
# launch, in release builds only.
-keep class io.github.resticdroid.ResticDroidApp { *; }
-keep class * implements androidx.work.Configuration$Provider { *; }
