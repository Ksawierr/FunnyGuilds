package net.dzikoysk.funnyguilds.element.tablist.variable.impl;

import net.dzikoysk.funnyguilds.basic.User;
import net.dzikoysk.funnyguilds.element.tablist.variable.TablistVariable;
import net.dzikoysk.funnyguilds.util.commons.ChatUtils;

import java.util.function.Function;

public class TimeFormattedVariable implements TablistVariable {
    private final String[] names;
    private final Function<User, Integer> function;

    public TimeFormattedVariable(String name, Function<User, Integer> function) {
        this(new String[]{ name }, function);
    }

    public TimeFormattedVariable(String[] names, Function<User, Integer> function) {
        this.names = names;
        this.function = function;
    }

    @Override
    public String[] names() {
        return this.names;
    }

    @Override
    public String get(User user) {
        return ChatUtils.appendDigit(this.function.apply(user));
    }
    
}
