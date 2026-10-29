package org.mydreamduels.dreammultiverse.command;

import org.bukkit.command.CommandSender;

import java.util.List;

/** One subcommand of a CommandRouter. */
public final class Sub {

    @FunctionalInterface
    public interface Exec {
        void run(CommandSender sender, String[] args);
    }

    @FunctionalInterface
    public interface Tab {
        List<String> complete(CommandSender sender, String[] args);
    }

    final String name;
    final List<String> aliases;
    final String permission;
    final String usage;
    final String description;
    final int minArgs;
    final boolean playerOnly;
    final Exec exec;
    final Tab tab;

    private Sub(Builder b) {
        this.name = b.name;
        this.aliases = b.aliases;
        this.permission = b.permission;
        this.usage = b.usage;
        this.description = b.description;
        this.minArgs = b.minArgs;
        this.playerOnly = b.playerOnly;
        this.exec = b.exec;
        this.tab = b.tab;
    }

    public static Builder of(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private List<String> aliases = List.of();
        private String permission;
        private String usage = "";
        private String description = "";
        private int minArgs;
        private boolean playerOnly;
        private Exec exec;
        private Tab tab = (s, a) -> List.of();

        private Builder(String name) {
            this.name = name;
        }

        public Builder aliases(String... aliases) {
            this.aliases = List.of(aliases);
            return this;
        }

        public Builder perm(String permission) {
            this.permission = permission;
            return this;
        }

        public Builder usage(String usage) {
            this.usage = usage;
            return this;
        }

        public Builder desc(String description) {
            this.description = description;
            return this;
        }

        public Builder min(int minArgs) {
            this.minArgs = minArgs;
            return this;
        }

        public Builder playerOnly() {
            this.playerOnly = true;
            return this;
        }

        public Builder tab(Tab tab) {
            this.tab = tab;
            return this;
        }

        public Sub exec(Exec exec) {
            this.exec = exec;
            return new Sub(this);
        }
    }
}
