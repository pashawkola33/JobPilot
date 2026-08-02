/**
 * Non-queuing admission control for expensive browser work.
 *
 * A caller either acquires a fixed render slot immediately or is rejected.
 * There is deliberately no promise waiter list and therefore no hidden,
 * unbounded application queue behind Node's HTTP server.
 */
export class AdmissionController {
  private active = 0;

  constructor(private readonly capacity: number) {
    if (!Number.isInteger(capacity) || capacity < 1) {
      throw new Error("Admission capacity must be a positive integer");
    }
  }

  tryAcquire(): (() => void) | null {
    if (this.active >= this.capacity) return null;
    this.active++;
    let released = false;
    return () => {
      if (released) return;
      released = true;
      this.active--;
    };
  }

  snapshot(): { active: number; capacity: number; queued: 0 } {
    return { active: this.active, capacity: this.capacity, queued: 0 };
  }
}
