package net.dzikoysk.funnyguilds.command;

import net.dzikoysk.funnyguilds.FunnyGuilds;
import net.dzikoysk.funnyguilds.command.util.Executor;
import net.dzikoysk.funnyguilds.data.configs.PluginConfiguration;
import net.dzikoysk.funnyguilds.element.gui.GuiItem;
import net.dzikoysk.funnyguilds.element.gui.GuiWindow;
import net.dzikoysk.funnyguilds.util.commons.ChatUtils;
import net.dzikoysk.funnyguilds.util.commons.bukkit.ItemUtils;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ExcItems implements Executor {

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        PluginConfiguration config = FunnyGuilds.getInstance().getPluginConfiguration();

        List<ItemStack> guiItems = config.guiItems;
        String title = config.guiItemsTitle;

        if (!config.useCommonGUI && player.hasPermission("funnyguilds.vip.items")) {
            guiItems = config.guiItemsVip;
            title = config.guiItemsVipTitle;
        }

        GuiWindow gui = new GuiWindow(title, guiItems.size() / 9 + (guiItems.size() % 9 != 0 ? 1 : 0));
        gui.setCloseEvent(close -> gui.unregister());

        for (ItemStack item : guiItems) {
            item = item.clone();

            if (config.addLoreLines && (config.createItems.contains(item) || config.createItemsVip.contains(item))) {
                ItemMeta meta = item.getItemMeta();

                if (meta == null) {
                    FunnyGuilds.getInstance().getLogger().warning("");
                    continue;
                }

                int requiredAmount = item.getAmount();
                int inventoryAmount = ItemUtils.getItemAmount(item, player.getInventory());
                int enderChestAmount = ItemUtils.getItemAmount(item, player.getEnderChest());

                List<String> lore = meta.getLore();

                if (lore == null) {
                    lore = new ArrayList<>(config.guiItemsLore.size());
                }

                for (String line : config.guiItemsLore) {
                    line = StringUtils.replace(line, "{REQ-AMOUNT}", Integer.toString(requiredAmount));
                    line = StringUtils.replace(line, "{PINV-AMOUNT}", Integer.toString(inventoryAmount));
                    line = StringUtils.replace(line, "{PINV-PERCENT}", ChatUtils.getPercent(inventoryAmount, requiredAmount));
                    line = StringUtils.replace(line, "{EC-AMOUNT}", Integer.toString(enderChestAmount));
                    line = StringUtils.replace(line, "{EC-PERCENT}", ChatUtils.getPercent(enderChestAmount, requiredAmount));
                    line = StringUtils.replace(line, "{ALL-AMOUNT}", Integer.toString(inventoryAmount + enderChestAmount));
                    line = StringUtils.replace(line, "{ALL-PERCENT}", ChatUtils.getPercent(inventoryAmount + enderChestAmount, requiredAmount));

                    lore.add(line);
                }

                if (!Objects.equals(config.guiItemsName, "")) {
                    meta.setDisplayName(ItemUtils.translateTextPlaceholder(config.guiItemsName, null, item));
                }

                meta.setLore(lore);
                item.setItemMeta(meta);
            }

            gui.setToNextFree(new GuiItem(item));
        }

        gui.open(player);
    }

}
