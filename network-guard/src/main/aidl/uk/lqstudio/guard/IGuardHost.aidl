package uk.lqstudio.guard;
import uk.lqstudio.guard.IGuard;
interface IGuardHost { void attach(in IGuard session); void detach(); }
