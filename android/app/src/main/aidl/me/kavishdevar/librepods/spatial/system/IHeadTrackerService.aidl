// LibrePods - AirPods liberated from Apple's ecosystem
// Copyright (C) 2025 LibrePods contributors
// GPL-3.0-or-later
//
// Roadmap #17. Adapted from the DroidThug/librepods fork (branch
// shizuku-head-tracked-spatial-audio, GPL-3.0-or-later).

package me.kavishdevar.librepods.spatial.system;

/**
 * The virtual head tracker, run by Shizuku with the shell's identity (the shell is in the uhid
 * group, an app never is) and called over Binder from the app: SELinux lets Binder cross between
 * an app and the shell where it blocks sockets.
 */
interface IHeadTrackerService {
    /** Creates the virtual head tracker for the AirPods at mac ("AA:BB:CC:DD:EE:FF"). */
    boolean start(String mac);

    /**
     * One pose: rotation vector rx, ry, rz (32767 = pi rad) and angular velocity vx, vy, vz
     * (32767 = 32 rad/s), both in Android's head frame; discontinuity changes when the reference
     * frame does. oneway: called for every motion sample and must not block.
     */
    oneway void sendPose(int rx, int ry, int rz, int vx, int vy, int vz, int discontinuity);

    /**
     * What Android last asked of the sensor: -1 if it never said, else bit 0 reporting (all
     * events), bit 1 full power, bits 2-7 the report interval (0-63 for 10-100 ms).
     */
    int hostState();

    /** Removes the virtual head tracker. */
    void stop();

    /** Removes it and ends the process. */
    void destroy();
}
