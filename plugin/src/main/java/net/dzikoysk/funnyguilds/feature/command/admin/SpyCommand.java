package net.dzikoysk.funnyguilds.feature.command.admin;

import net.dzikoysk.funnycommands.stereotypes.FunnyCommand;
import net.dzikoysk.funnyguilds.feature.command.AbstractFunnyCommand;
import net.dzikoysk.funnyguilds.feature.command.UserValidation;
import net.dzikoysk.funnyguilds.user.UserCache;
import org.bukkit.command.CommandSender;

public final class SpyCommand extends AbstractFunnyCommand {

    @FunnyCommand(
            name = "${admin.spy.name}",
            permission = "funnyguilds.admin",
            acceptsExceeded = true,
            playerOnly = true
    )
    public void execute(CommandSender sender) {
        UserCache userCache = UserValidation.requireUserByName(sender.getName()).getCache();
        this.sendMessage(sender, userCache.toggleSpy() ? this.messages.adminStartSpy : this.messages.adminStopSpy);
    }

}
