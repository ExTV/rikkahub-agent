package me.rerere.rikkahub.shizuku;

// AIDL contract for the process Shizuku spawns under the shell UID (bound via
// Shizuku.bindUserService — Shizuku.newProcess is private in dev.rikka.shizuku:api 13.1.5,
// verified with `javap -p` against the published artifact, so this is the supported way to
// run a command with Shizuku's privileges). See ShizukuManager.kt and ShizukuUserService.kt.
interface IShizukuUserService {

    // Runs `command` under a shell, honoring `timeoutMs`, and returns a JSON object string
    // with stdout / stderr / exit_code (or a structured error on timeout / launch failure).
    // See ShizukuCommandRunner.
    String exec(String command, int timeoutMs) = 1;

    // Reserved destroy method id required by Shizuku's server contract — it calls this to
    // tear the service down.
    void destroy() = 16777114;
}
