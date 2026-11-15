package me.server.tglink;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TgLinkPlugin extends JavaPlugin {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final SecureRandom random = new SecureRandom();

    // Код -> Данные заявки (ник и chat_id)
    private final Map<String, PendingLink> pendingCodes = new ConcurrentHashMap<>();

    private String botToken;
    private long lastUpdateId = 0;
    private volatile boolean running = true;

    private File linkedFile;
    private FileConfiguration linkedConfig;

    private static class PendingLink {
        final String nickname;
        final long chatId;

        PendingLink(String nickname, long chatId) {
            this.nickname = nickname;
            this.chatId = chatId;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        botToken = getConfig().getString("bot-token", "9166366241:AAGPo1nSLZBEaISb6KSpFIK04ExSOXH7Zf4");

        linkedFile = new File(getDataFolder(), "linked.yml");
        if (!linkedFile.exists()) {
            try {
                linkedFile.getParentFile().mkdirs();
                linkedFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        linkedConfig = YamlConfiguration.loadConfiguration(linkedFile);

        if (botToken.equals("9166366241:AAGPo1nSLZBEaISb6KSpFIK04ExSOXH7Zf4") || botToken.isEmpty()) {
            getLogger().warning("Укажите bot-token в plugins/TgLink/config.yml и перезапустите сервер!");
            return;
        }

        // Запускаем опрос Telegram в отдельном потоке, чтобы не нагружать сервер
        Thread poller = new Thread(this::pollTelegram, "TgLink-Poller");
        poller.setDaemon(true);
        poller.start();

        getLogger().info("TgLink успешно запущен!");
    }

    @Override
    public void onDisable() {
        running = false;
    }

    // Генерация 8-значного кода: 2 цифры + 4 буквы + 2 цифры (например, 19aGFh07)
    private String generateCode() {
        String letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder(8);
        sb.append(random.nextInt(10));
        sb.append(random.nextInt(10));
        for (int i = 0; i < 4; i++) {
            sb.append(letters.charAt(random.nextInt(letters.length())));
        }
        sb.append(random.nextInt(10));
        sb.append(random.nextInt(10));
        return sb.toString();
    }

    // Обработка команды /tg <код> в Minecraft
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Команда доступна только игрокам!");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage("§cИспользуйте: /tg <код_из_Telegram>");
            return true;
        }

        String inputCode = args[0].trim();
        PendingLink pending = pendingCodes.get(inputCode);

        if (pending == null) {
            player.sendMessage("§cНеверный или устаревший код!");
            return true;
        }

        if (!pending.nickname.equalsIgnoreCase(player.getName())) {
            player.sendMessage("§cЭтот код выдан для ника §e" + pending.nickname + "§c!");
            return true;
        }

        // Удаляем использованный код и сохраняем привязку
        pendingCodes.remove(inputCode);
        String exactNick = player.getName();
        linkedConfig.set("players." + exactNick.toLowerCase() + ".nickname", exactNick);
        linkedConfig.set("players." + exactNick.toLowerCase() + ".chat-id", pending.chatId);
        try {
            linkedConfig.save(linkedFile);
        } catch (IOException e) {
            e.printStackTrace();
        }

        String successMsg = "Аккаунт " + exactNick + " привязан!";
        player.sendMessage("§a" + successMsg);

        // Отправляем подтверждение в Telegram асинхронно
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> sendTelegramMessage(pending.chatId, successMsg));
        return true;
    }

    // Фоновый цикл получения сообщений из Telegram
    private void pollTelegram() {
        Pattern updatePattern = Pattern.compile("\"update_id\":(\\d+).*?\"chat\":\\{\"id\":(-?\\d+).*?\"text\":\"([^\"]+)\"");

        while (running) {
            try {
                String url = "https://telegram.org" + botToken + "/getUpdates?timeout=20&offset=" + (lastUpdateId + 1);
                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                String body = response.body();
                Matcher matcher = updatePattern.matcher(body);

                while (matcher.find()) {
                    long updateId = Long.parseLong(matcher.group(1));
                    long chatId = Long.parseLong(matcher.group(2));
                    String text = matcher.group(3).trim();

                    if (updateId > lastUpdateId) {
                        lastUpdateId = updateId;
                    }

                    handleIncomingMessage(chatId, text);
                }
            } catch (Exception ignored) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    private void handleIncomingMessage(long chatId, String text) {
        if (text.equalsIgnoreCase("/start")) {
            sendTelegramMessage(chatId, "Привет! Напиши свой игровой ник из Minecraft, чтобы получить код привязки.");
            return;
        }

        // Проверяем, что ник похож на валидный ник Minecraft (3-16 символов: буквы, цифры, _)
        if (!text.matches("^[a-zA-Z0-9_]{3,16}$")) {
            sendTelegramMessage(chatId, "Некорректный ник! Введи ник из Minecraft (от 3 до 16 символов, латиница и цифры).");
            return;
        }

        if (linkedConfig.contains("players." + text.toLowerCase())) {
            sendTelegramMessage(chatId, "Аккаунт " + text + " уже привязан!");
            return;
        }

        // Удаляем старые коды этого пользователя, если он запрашивал их ранее
        pendingCodes.entrySet().removeIf(entry -> entry.getValue().chatId == chatId);

        String code = generateCode();
        pendingCodes.put(code, new PendingLink(text, chatId));

        sendTelegramMessage(chatId,
                "Код привязки для ника " + text + ": " + code + "\n" +
                "Зайди на сервер и введи команду: /tg " + code);
    }

    private void sendTelegramMessage(long chatId, String text) {
        try {
            String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8);
            String url = "https://telegram.org" + botToken + "/sendMessage?chat_id=" + chatId + "&text=" + encodedText;
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            getLogger().warning("Ошибка отправки сообщения в Telegram: " + e.getMessage());
        }
    }
}
