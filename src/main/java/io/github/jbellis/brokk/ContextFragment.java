package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

public interface ContextFragment extends Serializable {
    /** short description in history */
    String shortDescription();
    /** longer description displayed in context table */
    String description();
    /** raw content */
    String text() throws IOException;
    /** content formatted for LLM */
    String format() throws IOException;

    /** code sources found in this fragment */
    default Set<CodeUnit> sources(IProject project) {
        return sources(project.getAnalyzer(), project.getRepo());
    }

    /** for when you don't want analyzer to change out from under you */
    Set<CodeUnit> sources(IAnalyzer analyzer, IGitRepo repo);

    /** should classes found in this fragment be included in AutoContext? */
    boolean isEligibleForAutoContext();

    sealed interface PathFragment extends ContextFragment 
        permits RepoPathFragment, ExternalPathFragment
    {
        BrokkFile file();

        default String text() throws IOException {
            return file().read();
        }

        default String format() throws IOException {
            return """
            <file path="%s">
            %s
            </file>
            """.formatted(file().toString(), text()).stripIndent();
        }
    }

    record RepoPathFragment(RepoFile file) implements PathFragment {
        private static final long serialVersionUID = 1L;
        @Override
        public String shortDescription() {
            return file().getFileName();
        }

        @Override
        public String description() {
            if (file.getParent().isEmpty()) {
                return file.getFileName();
            }
            return "%s [%s]".formatted(file.getFileName(), file.getParent());
        }

        @Override
        public Set<CodeUnit> sources(IAnalyzer analyzer, IGitRepo repo) {
            return analyzer.getClassesInFile(file);
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return true;
        }

        @Override
        public String toString() {
            return "RepoPathFragment('%s')".formatted(file);
        }
    }

    record ExternalPathFragment(ExternalFile file) implements PathFragment {
        private static final long serialVersionUID = 1L;
        @Override
        public String shortDescription() {
            return description();
        }

        @Override
        public String description() {
            return file.toString();
        }

        @Override
        public Set<CodeUnit> sources(IAnalyzer analyzer, IGitRepo repo) {
            return Set.of();
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }
    }

    static PathFragment toPathFragment(BrokkFile bf) {
        if (bf instanceof RepoFile repo) {
            return new RepoPathFragment(repo);
        } else if (bf instanceof ExternalFile ext) {
            return new ExternalPathFragment(ext);
        }
        throw new IllegalArgumentException("Unknown BrokkFile subtype: " + bf.getClass().getName());
    }

    abstract class VirtualFragment implements ContextFragment {
        @Override
        public String format() throws IOException {
            return """
            <fragment description="%s">
            %s
            </fragment>
            """.formatted(description(), text()).stripIndent();
        }

        @Override
        public String shortDescription() {
            assert !description().isEmpty();
            return description().substring(0, 1).toLowerCase() + description().substring(1);
        }

        @Override
        public Set<CodeUnit> sources(IAnalyzer analyzer, IGitRepo repo) {
            return repo.getTrackedFiles().stream().parallel()
                    .filter(f -> text().contains(f.toString()))
                    .flatMap(f -> analyzer.getClassesInFile(f).stream())
                    .collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public abstract String text(); // no exceptions
    }

    class StringFragment extends VirtualFragment {
        private static final long serialVersionUID = 1L;
        private final String text;
        private final String description;

        public StringFragment(String text, String description) {
            super();
            assert text != null;
            assert description != null;
            this.text = text;
            this.description = description;
        }

        @Override
        public String text() {
            return text;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return true;
        }

        @Override
        public String toString() {
            return "StringFragment('%s')".formatted(description);
        }
    }

    class SearchFragment extends VirtualFragment {
        private static final long serialVersionUID = 1L;
        private final String query;
        private final String explanation;
        private final Set<CodeUnit> sources;

        public SearchFragment(String query, String explanation, Set<CodeUnit> sources) {
            super();
            assert query != null;
            assert explanation != null;
            assert sources != null;
            this.query = query;
            this.explanation = explanation;
            this.sources = sources;
        }

        @Override
        public String text() {
            return explanation;
        }

        @Override
        public Set<CodeUnit> sources(IAnalyzer analyzer, IGitRepo repo) {
            return sources;
        }

        @Override
        public String description() {
            return "Search: " + query;
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return true;
        }

        @Override
        public String toString() {
            return "SearchFragment('%s')".formatted(query);
        }
    }

    class PasteFragment extends VirtualFragment {
        private static final long serialVersionUID = 1L;
        private final String text;
        private transient Future<String> descriptionFuture;

        public PasteFragment(String text, Future<String> descriptionFuture) {
            super();
            assert text != null;
            assert descriptionFuture != null;
            this.text = text;
            this.descriptionFuture = descriptionFuture;
        }

        @Override
        public String text() {
            return text;
        }

        @Override
        public String description() {
            if (descriptionFuture.isDone()) {
                try {
                    return "Paste of " + descriptionFuture.get();
                } catch (Exception e) {
                    return "(Error summarizing paste)";
                }
            }
            return "(Summarizing. This does not block LLM requests)";
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return true;
        }

        @Override
        public String toString() {
            return "PasteFragment('%s')".formatted(description());
        }
        
        private void writeObject(java.io.ObjectOutputStream out) throws IOException {
            out.defaultWriteObject();
            // Serialize the description as a String
            String description;
            if (descriptionFuture.isDone()) {
                try {
                    description = descriptionFuture.get();
                } catch (Exception e) {
                    description = "(Error summarizing paste)";
                }
            } else {
                description = "(Paste summary incomplete)";
            }
            out.writeObject(description);
        }
        
        private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            // Read the description and convert it back to a CompletableFuture
            String description = (String) in.readObject();
            this.descriptionFuture = java.util.concurrent.CompletableFuture.completedFuture(description);
        }
    }

    class StacktraceFragment extends VirtualFragment {
        private static final long serialVersionUID = 1L;
        private final Set<CodeUnit> sources;
        private final String original;
        private final String exception;
        private final String code;

        public StacktraceFragment(Set<CodeUnit> sources, String original, String exception, String code) {
            super();
            assert sources != null;
            assert original != null;
            assert exception != null;
            assert code != null;
            this.sources = sources;
            this.original = original;
            this.exception = exception;
            this.code = code;
        }

        @Override
        public String text() {
            return original + "\n\nStacktrace methods in this project:\n\n" + code;
        }

        @Override
        public Set<CodeUnit> sources(IAnalyzer analyzer, IGitRepo repo) {
            return sources;
        }

        @Override
        public String description() {
            return "stacktrace of " + exception;
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return true;
        }
    }

    static String toClassname(String methodname) {
        int lastDot = methodname.lastIndexOf('.');
        if (lastDot == -1) {
            return methodname;
        }
        return methodname.substring(0, lastDot);
    }

    class UsageFragment extends VirtualFragment {
        private static final long serialVersionUID = 1L;
        private final String targetIdentifier;
        private final Set<CodeUnit> classnames;
        private final String code;

        public UsageFragment(String targetIdentifier, Set<CodeUnit> classnames, String code) {
            super();
            assert targetIdentifier != null;
            assert classnames != null;
            assert code != null;
            this.targetIdentifier = targetIdentifier;
            this.classnames = classnames;
            this.code = code;
        }

        @Override
        public String text() {
            return code;
        }

        @Override
        public Set<CodeUnit> sources(IAnalyzer analyzer, IGitRepo repo) {
            return classnames;
        }

        @Override
        public String description() {
            return "Uses of %s".formatted(targetIdentifier);
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return true;
        }
    }

    class SkeletonFragment extends VirtualFragment {
        private static final long serialVersionUID = 2L;
        private final Map<CodeUnit, String> skeletons;

        public SkeletonFragment(Map<CodeUnit, String> skeletons) {
            super();
            assert skeletons != null;
            this.skeletons = skeletons;
        }

        @Override
        public String text() {
            // Group skeletons by package name
            var skeletonsByPackage = skeletons.entrySet().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    entry -> {
                        var fqName = entry.getKey().fqName();
                        int lastDotIndex = fqName.lastIndexOf('.');
                        return lastDotIndex == -1 ? "(default package)" : fqName.substring(0, lastDotIndex);
                    },
                    java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (v1, v2) -> v1,
                        java.util.LinkedHashMap::new
                    )
                ));
                
            // Build the text with package headers
            return skeletonsByPackage.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(packageEntry -> {
                    String packageHeader = "package " + packageEntry.getKey() + ";";
                    String packageSkeletons = String.join("\n\n", packageEntry.getValue().values());
                    return packageHeader + "\n\n" + packageSkeletons;
                })
                .collect(java.util.stream.Collectors.joining("\n\n"));
        }

        @Override
        public Set<CodeUnit> sources(IAnalyzer analyzer, IGitRepo repo) {
            return skeletons.keySet();
        }

        @Override
        public String description() {
            return "Summary of " + String.join(", ", skeletons.keySet().stream()
                                                      .map(CodeUnit::name)
                                                      .sorted()
                                                      .toList());
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }

        @Override
        public String format() throws IOException {
            return """
            <summary classes="%s">
            %s
            </summary>
            """.formatted(String.join(", ", skeletons.keySet().stream().map(CodeUnit::fqName).sorted().toList()), text()).stripIndent();
        }

        @Override
        public String toString() {
            return "SkeletonFragment('%s')".formatted(description());
        }
    }

    /**
     * A context fragment that holds a list of short class names and a text
     * representation (e.g. skeletons) of those classes.
     */
    class ConversationFragment extends VirtualFragment {
        private static final long serialVersionUID = 1L;
        private final List<ChatMessage> messages;

        public ConversationFragment(List<ChatMessage> messages) {
            super();
            assert messages != null;
            this.messages = List.copyOf(messages);
        }

        @Override
        public String text() {
            return messages.stream()
                .map(m -> m.type() + ": " + Models.getText(m))
                .collect(java.util.stream.Collectors.joining("\n\n"));
        }

        @Override
        public Set<CodeUnit> sources(IAnalyzer analyzer, IGitRepo repo) {
            return Set.of(); // Conversation history doesn't contain code sources
        }

        @Override
        public String description() {
            return "Conversation history (" + messages.size() + " messages)";
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }

        @Override
        public String format() {
            return """
            <conversation>
            %s
            </conversation>
            """.formatted(text()).stripIndent();
        }

        @Override
        public String toString() {
            return "ConversationFragment(" + messages.size() + " messages)";
        }

        public List<ChatMessage> getMessages() {
            return messages;
        }
    }

    record AutoContext(SkeletonFragment fragment) implements ContextFragment {
        private static final long serialVersionUID = 2L;
        public static final AutoContext EMPTY = new AutoContext(new SkeletonFragment(Map.of(CodeUnit.cls("Enabled, but no references found"), "")));
        public static final AutoContext DISABLED = new AutoContext(new SkeletonFragment(Map.of(CodeUnit.cls("Disabled"), "")));
        public static final AutoContext UNAVAILABLE = new AutoContext(new SkeletonFragment(Map.of(CodeUnit.cls("Unavailable"), "")));

        public AutoContext {
            assert fragment != null;
        }

        @Override
        public String text() {
            return fragment.text();
        }

        @Override
        public Set<CodeUnit> sources(IAnalyzer analyzer, IGitRepo repo) {
            return fragment.sources(analyzer, repo);
        }

        /**
         * Returns a comma-separated list of short class names (no package).
         */
        @Override
        public String description() {
            return "[Auto] " + fragment.skeletons.keySet().stream()
                    .map(CodeUnit::name)
                    .collect(java.util.stream.Collectors.joining(", "));
        }

        @Override
        public String shortDescription() {
            return "Autosummary of " + fragment.skeletons.keySet().stream()
                    .map(CodeUnit::name)
                    .collect(java.util.stream.Collectors.joining(", "));
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }

        @Override
        public String format() throws IOException {
            return fragment.format();
        }

        @Override
        public String toString() {
            return "AutoContext('%s')".formatted(description());
        }
    }
}
