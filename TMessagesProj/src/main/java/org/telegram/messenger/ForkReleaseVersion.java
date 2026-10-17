package org.telegram.messenger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Foldgram release tag "foldgram-&lt;upstream build&gt;-&lt;fork revision&gt;", e.g. foldgram-7112-1.
 * Kept free of Android dependencies so the comparison can be tested on a plain JVM.
 */
public final class ForkReleaseVersion {

    private static final Pattern TAG_PATTERN = Pattern.compile("^foldgram-(\\d{1,9})-(\\d{1,4})$");

    public final int upstreamBuild;
    public final int revision;

    public ForkReleaseVersion(int upstreamBuild, int revision) {
        this.upstreamBuild = upstreamBuild;
        this.revision = revision;
    }

    /** @return the parsed version, or null if the tag isn't a Foldgram release tag. */
    public static ForkReleaseVersion parse(String tag) {
        if (tag == null) {
            return null;
        }
        Matcher matcher = TAG_PATTERN.matcher(tag.trim());
        if (!matcher.matches()) {
            return null;
        }
        return new ForkReleaseVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    public boolean isNewerThan(int otherUpstreamBuild, int otherRevision) {
        if (upstreamBuild != otherUpstreamBuild) {
            return upstreamBuild > otherUpstreamBuild;
        }
        return revision > otherRevision;
    }

    public String toTag() {
        return "foldgram-" + upstreamBuild + "-" + revision;
    }
}
