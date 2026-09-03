/**
 * harness-fs-fs — filesystem capability Definition (design: docs/design/05-capability-seam.md §4).
 * Providers implement; consumers see only this contract.
 */
module io.javanatic.harness.fs.fs {
    requires io.javanatic.harness.kernel;

    exports io.javanatic.harness.fs;
}
