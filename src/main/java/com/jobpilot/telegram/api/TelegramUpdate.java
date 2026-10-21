package com.jobpilot.telegram.api;

public record TelegramUpdate(long updateId, TelegramMessage message, TelegramCallbackQuery callbackQuery) {
    public record TelegramMessage(long messageId, TelegramUser from, TelegramChat chat, String text,Long date,boolean forwarded) {
        public TelegramMessage(long messageId,TelegramUser from,TelegramChat chat,String text){this(messageId,from,chat,text,null,false);}
    }
    public record TelegramCallbackQuery(String id,TelegramUser from,TelegramMessage message,String data) { }
    public record TelegramUser(long id,Boolean isBot) { public TelegramUser(long id){this(id,null);} }
    public record TelegramChat(long id,String type) { public TelegramChat(long id){this(id,null);} }
}
