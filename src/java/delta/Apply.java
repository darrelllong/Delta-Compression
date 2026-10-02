package delta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static delta.Types.*;

/** Placing commands in the version, and applying them to a reference. */
public final class Apply {
    private Apply() {}

    /** Returns the length of the version that commands build. */
    public static long outputSize(List<Command> commands) {
        long size = 0;
        for (Command cmd : commands) {
            if (cmd instanceof CopyCmd c) size += c.length();
            else if (cmd instanceof AddCmd a) size += a.data().length;
        }
        return size;
    }

    /** Gives each command its destination: the commands write the version left to right. */
    public static List<PlacedCommand> placeCommands(List<Command> commands) {
        List<PlacedCommand> placed = new ArrayList<>(commands.size());
        long dst = 0;
        for (Command cmd : commands) {
            if (cmd instanceof CopyCmd c) {
                placed.add(new PlacedCopy(c.offset(), dst, c.length()));
                dst += c.length();
            } else if (cmd instanceof AddCmd a) {
                placed.add(new PlacedAdd(dst, a.data()));
                dst += a.data().length;
            }
        }
        return placed;
    }

    /**
     * The inverse of {@link #placeCommands}: orders the commands by
     * destination and drops the destinations.
     *
     * @throws IllegalArgumentException if a command is a PlacedMove
     */
    public static List<Command> unplaceCommands(List<PlacedCommand> placed) {
        List<PlacedCommand> sorted = new ArrayList<>(placed);
        sorted.sort(Comparator.comparingLong(Apply::destination));
        List<Command> commands = new ArrayList<>(sorted.size());
        for (PlacedCommand cmd : sorted) {
            if (cmd instanceof PlacedCopy c) {
                commands.add(new CopyCmd(c.src(), c.length()));
            } else if (cmd instanceof PlacedAdd a) {
                commands.add(new AddCmd(a.data()));
            } else {
                throw new IllegalArgumentException("PlacedMove has no algorithm-level equivalent");
            }
        }
        return commands;
    }

    private static long destination(PlacedCommand cmd) {
        if (cmd instanceof PlacedCopy c) return c.dst();
        if (cmd instanceof PlacedAdd a) return a.dst();
        return ((PlacedMove) cmd).dst();
    }

    /**
     * Returns commands that build the same version as {@code commands} when
     * run in the buffer that holds r (Burns, Long and Stockmeyer, 2003).
     * The copies come first, ordered so that none reads bytes already
     * overwritten, and then the adds.  Where copies depend on one another in
     * a cycle, policy chooses one to replace by an add of the bytes of r.
     */
    public static List<PlacedCommand> makeInplace(byte[] r, List<Command> commands,
                                                   CyclePolicy policy) {
        return Inplace.convert(r, commands, policy);
    }

    /**
     * Checks that every command stays within the version and within its
     * source, so that applying them cannot go out of bounds.  An in-place
     * copy may read anywhere in the buffer, which is as long as the longer
     * of the reference and the version.
     *
     * @throws IllegalArgumentException if a command does not
     */
    public static void validatePlacedCommands(List<PlacedCommand> commands,
                                              long referenceSize, long versionSize,
                                              boolean inplace) {
        long sourceLimit = inplace ? Math.max(referenceSize, versionSize) : referenceSize;
        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy c) {
                checkRange(c.dst(), c.length(), versionSize, "copy destination");
                checkRange(c.src(), c.length(), sourceLimit, "copy source");
            } else if (cmd instanceof PlacedAdd a) {
                checkRange(a.dst(), a.data().length, versionSize, "add destination");
            } else if (cmd instanceof PlacedMove m) {
                checkRange(m.dst(), m.length(), versionSize, "move destination");
                // src + length <= dst, written so that it cannot overflow.
                if (m.src() < 0 || m.src() > m.dst() - m.length()) {
                    throw new IllegalArgumentException(
                        "MOVE src+length > dst: encoder ordering constraint violated");
                }
            }
        }
    }

    private static void checkRange(long start, long len, long limit, String what) {
        if (start < 0 || len < 0 || start > limit || len > limit - start) {
            throw new IllegalArgumentException(what + " out of range");
        }
    }

    /**
     * Runs the commands of a standard delta: copies read r, moves read out,
     * and everything is written to out.  The commands must be valid for r
     * and out.  Returns the largest dst + length among them.
     */
    public static long applyPlacedTo(byte[] r, List<PlacedCommand> commands, byte[] out) {
        long end = 0;
        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy c) {
                System.arraycopy(r, (int) c.src(), out, (int) c.dst(), (int) c.length());
                end = Math.max(end, c.dst() + c.length());
            } else if (cmd instanceof PlacedAdd a) {
                System.arraycopy(a.data(), 0, out, (int) a.dst(), a.data().length);
                end = Math.max(end, a.dst() + a.data().length);
            } else if (cmd instanceof PlacedMove m) {
                System.arraycopy(out, (int) m.src(), out, (int) m.dst(), (int) m.length());
                end = Math.max(end, m.dst() + m.length());
            }
        }
        return end;
    }

    /**
     * Runs the commands of an in-place delta in buf, which holds the
     * reference and is at least as long as the version.  A copy may overlap
     * its own destination.
     */
    public static void applyPlacedInplaceTo(List<PlacedCommand> commands, byte[] buf) {
        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy c) {
                System.arraycopy(buf, (int) c.src(), buf, (int) c.dst(), (int) c.length());
            } else if (cmd instanceof PlacedAdd a) {
                System.arraycopy(a.data(), 0, buf, (int) a.dst(), a.data().length);
            } else if (cmd instanceof PlacedMove m) {
                System.arraycopy(buf, (int) m.src(), buf, (int) m.dst(), (int) m.length());
            }
        }
    }

    /** Returns the version that commands build from r. */
    public static byte[] applyDelta(byte[] r, List<Command> commands) {
        long size = outputSize(commands);
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("output size too large for JVM");
        }
        byte[] out = new byte[(int) size];
        int pos = 0;
        for (Command cmd : commands) {
            if (cmd instanceof CopyCmd c) {
                System.arraycopy(r, (int) c.offset(), out, pos, (int) c.length());
                pos += (int) c.length();
            } else if (cmd instanceof AddCmd a) {
                System.arraycopy(a.data(), 0, out, pos, a.data().length);
                pos += a.data().length;
            }
        }
        return out;
    }

    /** Returns the version that in-place commands build from r; r itself is not modified. */
    public static byte[] applyDeltaInplace(byte[] r, List<PlacedCommand> commands,
                                           long versionSize) {
        long bufSize = Math.max(r.length, versionSize);
        if (bufSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("buffer size too large for JVM");
        }
        byte[] buf = Arrays.copyOf(r, (int) bufSize);
        applyPlacedInplaceTo(commands, buf);
        return buf.length == versionSize ? buf : Arrays.copyOf(buf, (int) versionSize);
    }

    /** Counts the commands and the bytes they produce. */
    public static PlacedSummary placedSummary(List<PlacedCommand> commands) {
        int numCopies = 0, numAdds = 0;
        long copyBytes = 0, addBytes = 0;
        for (PlacedCommand cmd : commands) {
            if (cmd instanceof PlacedCopy c) {
                numCopies++;
                copyBytes += c.length();
            } else if (cmd instanceof PlacedAdd a) {
                numAdds++;
                addBytes += a.data().length;
            } else if (cmd instanceof PlacedMove m) {
                numCopies++;
                copyBytes += m.length();
            }
        }
        return new PlacedSummary(commands.size(), numCopies, numAdds,
            copyBytes, addBytes, copyBytes + addBytes);
    }
}
