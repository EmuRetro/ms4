package com.eu.habbo.habbohotel.rooms;

import com.eu.habbo.Emulator;
import com.eu.habbo.database.DatabaseConstants;
import com.eu.habbo.habbohotel.achievements.AchievementManager;
import com.eu.habbo.habbohotel.bots.Bot;
import com.eu.habbo.habbohotel.bots.VisitorBot;
import com.eu.habbo.habbohotel.games.Game;
import com.eu.habbo.habbohotel.guilds.Guild;
import com.eu.habbo.habbohotel.guilds.GuildMember;
import com.eu.habbo.habbohotel.items.FurnitureType;
import com.eu.habbo.habbohotel.items.ICycleable;
import com.eu.habbo.habbohotel.items.Item;
import com.eu.habbo.habbohotel.items.interactions.*;
import com.eu.habbo.habbohotel.items.interactions.games.InteractionGameGate;
import com.eu.habbo.habbohotel.items.interactions.games.InteractionGameScoreboard;
import com.eu.habbo.habbohotel.items.interactions.games.InteractionGameTimer;
import com.eu.habbo.habbohotel.items.interactions.games.battlebanzai.InteractionBattleBanzaiSphere;
import com.eu.habbo.habbohotel.items.interactions.games.battlebanzai.InteractionBattleBanzaiTeleporter;
import com.eu.habbo.habbohotel.items.interactions.games.freeze.InteractionFreezeExitTile;
import com.eu.habbo.habbohotel.items.interactions.games.tag.InteractionTagField;
import com.eu.habbo.habbohotel.items.interactions.games.tag.InteractionTagPole;
import com.eu.habbo.habbohotel.items.interactions.pets.*;
import com.eu.habbo.habbohotel.items.interactions.wired.extra.WiredBlob;
import com.eu.habbo.habbohotel.messenger.MessengerBuddy;
import com.eu.habbo.habbohotel.permissions.Permission;
import com.eu.habbo.habbohotel.pets.Pet;
import com.eu.habbo.habbohotel.pets.PetManager;
import com.eu.habbo.habbohotel.pets.RideablePet;
import com.eu.habbo.habbohotel.users.*;
import com.eu.habbo.habbohotel.wired.WiredHandler;
import com.eu.habbo.habbohotel.wired.WiredTriggerType;
import com.eu.habbo.messages.ISerialize;
import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.outgoing.MessageComposer;
import com.eu.habbo.messages.outgoing.generic.alerts.GenericErrorComposer;
import com.eu.habbo.messages.outgoing.generic.alerts.HabboBroadcastMessageComposer;
import com.eu.habbo.messages.outgoing.guilds.HabboGroupDetailsMessageComposer;
import com.eu.habbo.messages.outgoing.hotelview.CloseConnectionMessageComposer;
import com.eu.habbo.messages.outgoing.inventory.FurniListInvalidateComposer;
import com.eu.habbo.messages.outgoing.inventory.PetAddedToInventoryComposer;
import com.eu.habbo.messages.outgoing.inventory.UnseenItemsComposer;
import com.eu.habbo.messages.outgoing.polls.infobus.QuestionAnsweredComposer;
import com.eu.habbo.messages.outgoing.polls.infobus.QuestionComposer;
import com.eu.habbo.messages.outgoing.rooms.*;
import com.eu.habbo.messages.outgoing.rooms.items.*;
import com.eu.habbo.messages.outgoing.rooms.pets.RoomPetComposer;
import com.eu.habbo.messages.outgoing.rooms.users.*;
import com.eu.habbo.messages.outgoing.users.RemainingMutePeriodComposer;
import com.eu.habbo.plugin.Event;
import com.eu.habbo.plugin.events.furniture.*;
import com.eu.habbo.plugin.events.rooms.RoomLoadedEvent;
import com.eu.habbo.plugin.events.rooms.RoomUnloadedEvent;
import com.eu.habbo.plugin.events.rooms.RoomUnloadingEvent;
import com.eu.habbo.plugin.events.users.*;
import com.eu.habbo.threading.runnables.YouAreAPirate;
import gnu.trove.TCollections;
import gnu.trove.iterator.TIntObjectIterator;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.THashMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.hash.THashSet;
import io.netty.util.internal.ConcurrentSet;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.util.Pair;

import java.awt.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.eu.habbo.database.DatabaseConstants.CAUGHT_SQL_EXCEPTION;

@Slf4j
public class Room implements Comparable<Room>, ISerialize, Runnable {
    private static final String CAUGHT_EXCEPTION = "Caught exception";

    public static final Comparator<Room> SORT_SCORE = (o1, o2) -> o2.getScore() - o1.getScore();
    public static final Comparator<Room> SORT_ID = (o1, o2) -> o2.getId() - o1.getId();
    private static final TIntObjectHashMap<RoomMoodlightData> defaultMoodData = new TIntObjectHashMap<>();
    //Configuration. Loaded from database & updated accordingly.
    public static boolean HABBO_CHAT_DELAY = false;
    public static int MAXIMUM_BOTS = 10;
    public static int MAXIMUM_PETS = 10;
    public static int MAXIMUM_FURNI = 2500;
    public static int MAXIMUM_POSTITNOTES = 200;
    public static int HAND_ITEM_TIME = 10;
    public static int IDLE_CYCLES = 240;
    public static int IDLE_CYCLES_KICK = 480;
    public static String PREFIX_FORMAT = "[<font color=\"%color%\">%prefix%</font>] ";
    public static int ROLLERS_MAXIMUM_ROLL_AVATARS = 1;
    public static boolean MUTEAREA_CAN_WHISPER = false;
    public static final double MAXIMUM_FURNI_HEIGHT = 40d;


    static {
        for (int i = 1; i <= 3; i++) {
            RoomMoodlightData data = RoomMoodlightData.fromString("");
            data.setId(i);
            defaultMoodData.put(i, data);
        }
    }

    public final Object roomUnitLock = new Object();
    public final ConcurrentHashMap<RoomTile, THashSet<HabboItem>> tileCache = new ConcurrentHashMap<>();
    public final List<Integer> userVotes;
    @Getter
    private final ConcurrentHashMap<Integer, Habbo> currentHabbos = new ConcurrentHashMap<>(3);
    @Getter
    private final TIntObjectMap<Habbo> habboQueue = TCollections.synchronizedMap(new TIntObjectHashMap<>(0));
    @Getter
    private final TIntObjectMap<Bot> currentBots = TCollections.synchronizedMap(new TIntObjectHashMap<>(0));
    @Getter
    private final TIntObjectMap<Pet> currentPets = TCollections.synchronizedMap(new TIntObjectHashMap<>(0));
    private final THashSet<RoomTrade> activeTrades;
    @Getter
    private final TIntArrayList rights;
    private final TIntIntHashMap mutedHabbos;
    private final TIntObjectHashMap<RoomBan> bannedHabbos;
    @Getter
    private final ConcurrentSet<Game> games;
    @Getter
    private final TIntObjectMap<String> furniOwnerNames;
    @Getter
    private final TIntIntMap furniOwnerCount;
    @Getter
    private final TIntObjectMap<RoomMoodlightData> moodlightData;
    @Getter
    private final THashSet<String> wordFilterWords;
    private final TIntObjectMap<HabboItem> roomItems;
    private final Object loadLock = new Object();
    //Use appropriately. Could potentially cause memory leaks when used incorrectly.
    public volatile boolean preventUnloading = false;
    public volatile boolean preventUncaching = false;
    public final ConcurrentHashMap.KeySetView<ServerMessage, Boolean> scheduledComposers = ConcurrentHashMap.newKeySet();
    public ConcurrentHashMap.KeySetView<Runnable, Boolean> scheduledTasks = ConcurrentHashMap.newKeySet();
    @Getter
    private String wordQuiz = "";
    @Getter
    private int noVotes = 0;
    @Getter
    private int yesVotes = 0;
    @Getter
    private int wordQuizEnd = 0;
    public ScheduledFuture<?> roomCycleTask;
    @Getter
    private final int id;
    @Getter
    @Setter
    private int ownerId;
    @Getter
    @Setter
    private String ownerName;
    @Getter
    private String name;
    @Getter
    private String description;
    @Getter
    @Setter
    private RoomLayout layout;
    private boolean overrideModel;
    private final String layoutName;
    @Getter
    private String password;
    @Getter
    @Setter
    private RoomState state;
    @Setter
    @Getter
    private int usersMax;
    @Getter
    @Setter
    private volatile int score;
    @Getter
    @Setter
    private volatile int category;
    @Getter
    @Setter
    private String floorPaint;
    @Getter
    @Setter
    private String wallPaint;
    @Getter
    @Setter
    private String backgroundPaint;
    @Getter
    @Setter
    private int wallSize;
    @Getter
    @Setter
    private int wallHeight;
    @Getter
    @Setter
    private int floorSize;
    @Setter
    @Getter
    private int guildId;
    @Getter
    @Setter
    private String tags;
    @Setter
    @Getter
    private volatile boolean publicRoom;
    @Setter
    @Getter
    private volatile boolean staffPromotedRoom;
    @Getter
    private volatile boolean allowPets;
    @Setter
    @Getter
    private volatile boolean allowPetsEat;
    @Setter
    @Getter
    private volatile boolean allowWalkthrough;
    @Setter
    @Getter
    private volatile boolean allowBotsWalk;
    @Setter
    @Getter
    private volatile boolean allowEffects;
    @Setter
    @Getter
    private volatile boolean hideWall;
    @Setter
    @Getter
    private volatile int chatMode;
    @Setter
    @Getter
    private volatile int chatWeight;
    @Setter
    @Getter
    private volatile int chatSpeed;
    @Setter
    @Getter
    private volatile int chatDistance;
    @Setter
    @Getter
    private volatile int chatProtection;
    @Setter
    @Getter
    private volatile int muteOption;
    @Setter
    @Getter
    private volatile int kickOption;
    @Setter
    @Getter
    private volatile int banOption;
    @Setter
    @Getter
    private volatile int pollId;
    private volatile boolean promoted;
    @Getter
    @Setter
    private volatile int tradeMode;
    private volatile boolean moveDiagonally;
    private volatile boolean jukeboxActive;
    private volatile boolean hideWired;
    @Getter
    private RoomPromotion promotion;
    @Setter
    private volatile boolean needsUpdate;
    @Getter
    private volatile boolean loaded;
    @Getter
    private volatile boolean preLoaded;
    private int idleCycles;
    private volatile int unitCounter;
    @Getter
    private volatile int rollerSpeed;
    private final int muteTime = Emulator.getConfig().getInt("hotel.flood.mute.time", 30);
    private long rollerCycle = System.currentTimeMillis();
    @Setter
    @Getter
    private volatile int lastTimerReset = Emulator.getIntUnixTimestamp();
    @Setter
    @Getter
    private volatile boolean muted;
    @Getter
    private RoomSpecialTypes roomSpecialTypes;
    @Getter
    private TraxManager traxManager;
    private boolean cycleOdd;
    @Getter
    private long cycleTimestamp;

    public Room(ResultSet set) throws SQLException {
        this.id = set.getInt("id");
        this.ownerId = set.getInt("owner_id");
        this.ownerName = set.getString("owner_name");
        this.name = set.getString("name");
        this.description = set.getString("description");
        this.password = set.getString("password");
        this.state = RoomState.valueOf(set.getString("state").toUpperCase());
        this.usersMax = set.getInt("users_max");
        this.score = set.getInt("score");
        this.category = set.getInt("category");
        this.floorPaint = set.getString("paper_floor");
        this.wallPaint = set.getString("paper_wall");
        this.backgroundPaint = set.getString("paper_landscape");
        this.wallSize = set.getInt("thickness_wall");
        this.wallHeight = set.getInt("wall_height");
        this.floorSize = set.getInt("thickness_floor");
        this.tags = set.getString("tags");
        this.publicRoom = set.getBoolean("is_public");
        this.staffPromotedRoom = set.getBoolean("is_staff_picked");
        this.allowPets = set.getBoolean("allow_other_pets");
        this.allowPetsEat = set.getBoolean("allow_other_pets_eat");
        this.allowWalkthrough = set.getBoolean("allow_walkthrough");
        this.hideWall = set.getBoolean("allow_hidewall");
        this.chatMode = set.getInt("chat_mode");
        this.chatWeight = set.getInt("chat_weight");
        this.chatSpeed = set.getInt("chat_speed");
        this.chatDistance = set.getInt("chat_hearing_distance");
        this.chatProtection = set.getInt("chat_protection");
        this.muteOption = set.getInt("who_can_mute");
        this.kickOption = set.getInt("who_can_kick");
        this.banOption = set.getInt("who_can_ban");
        this.pollId = set.getInt("poll_id");
        this.guildId = set.getInt("guild_id");
        this.rollerSpeed = set.getInt("roller_speed");
        this.overrideModel = set.getString("override_model").equals("1");
        this.layoutName = set.getString("model");
        this.promoted = set.getString("promoted").equals("1");
        this.jukeboxActive = set.getString("jukebox_active").equals("1");
        this.hideWired = set.getString("hidewired").equals("1");

        this.bannedHabbos = new TIntObjectHashMap<>();

        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM room_promotions WHERE room_id = ? AND end_timestamp > ? LIMIT 1")) {
            if (this.promoted) {
                statement.setInt(1, this.id);
                statement.setInt(2, Emulator.getIntUnixTimestamp());

                try (ResultSet promotionSet = statement.executeQuery()) {
                    this.promoted = false;
                    if (promotionSet.next()) {
                        this.promoted = true;
                        this.promotion = new RoomPromotion(this, promotionSet);
                    }
                }
            }

            this.loadBans(connection);
        } catch (SQLException e) {
            log.error(CAUGHT_SQL_EXCEPTION, e);
        }

        this.tradeMode = set.getInt("trade_mode");
        this.moveDiagonally = set.getString("move_diagonally").equals("1");

        this.preLoaded = true;
        this.allowBotsWalk = true;
        this.allowEffects = true;
        this.furniOwnerNames = TCollections.synchronizedMap(new TIntObjectHashMap<>(0));
        this.furniOwnerCount = TCollections.synchronizedMap(new TIntIntHashMap(0));
        this.roomItems = TCollections.synchronizedMap(new TIntObjectHashMap<>(0));
        this.wordFilterWords = new THashSet<>(0);
        this.moodlightData = new TIntObjectHashMap<>(defaultMoodData);

        for (String s : set.getString("moodlight_data").split(";")) {
            RoomMoodlightData data = RoomMoodlightData.fromString(s);
            this.moodlightData.put(data.getId(), data);
        }

        this.mutedHabbos = new TIntIntHashMap();
        this.games = new ConcurrentSet<>();

        this.activeTrades = new THashSet<>(0);
        this.rights = new TIntArrayList();
        this.userVotes = new ArrayList<>();
    }

    public synchronized void loadData() {
        synchronized (this.loadLock) {
            if (!this.preLoaded || this.loaded)
                return;

            this.preLoaded = false;

            try (Connection connection = Emulator.getDatabase().getDataSource().getConnection()) {
                synchronized (this.roomUnitLock) {
                    this.unitCounter = 0;
                    this.currentHabbos.clear();
                    this.currentPets.clear();
                    this.currentBots.clear();
                }

                this.roomSpecialTypes = new RoomSpecialTypes();

                this.loadLayout();
                this.loadRights(connection);
                this.loadItems(connection);
                this.loadHeightmap();
                this.loadBots(connection);
                this.loadPets(connection);
                this.loadWordFilter(connection);
                this.loadWiredData(connection);

                this.idleCycles = 0;
                this.loaded = true;

                this.roomCycleTask = Emulator.getThreading().getService().scheduleAtFixedRate(this, 500, 500, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.error(CAUGHT_EXCEPTION, e);
            }

            this.traxManager = new TraxManager(this);

            if (this.jukeboxActive) {
                this.traxManager.play(0);
                for (HabboItem item : this.roomSpecialTypes.getItemsOfType(InteractionJukeBox.class)) {
                    this.updateItem(item.setExtradata("1"));
                }
            }

            for (HabboItem item : this.roomSpecialTypes.getItemsOfType(InteractionFireworks.class)) {
                this.updateItem(item.setExtradata("1"));
            }
        }

        Emulator.getPluginManager().fireEvent(new RoomLoadedEvent(this));
    }

    private synchronized void loadLayout() {
        try {
            if (this.layout == null) {
                if (this.overrideModel) {
                    this.layout = Emulator.getGameEnvironment().getRoomManager().loadCustomLayout(this);
                } else {
                    this.layout = Emulator.getGameEnvironment().getRoomManager().loadLayout(this.layoutName, this);
                }
            }
        } catch (Exception e) {
            log.error(CAUGHT_EXCEPTION, e);
        }
    }

    private synchronized void loadHeightmap() {
        try {
            if (this.layout != null) {
                for (short x = 0; x < this.layout.getMapSizeX(); x++) {
                    for (short y = 0; y < this.layout.getMapSizeY(); y++) {
                        RoomTile tile = this.layout.getTile(x, y);
                        if (tile != null) {
                            this.updateTile(tile);
                        }
                    }
                }
            } else {
                log.error("Unknown Room Layout for Room (ID: {})", this.id);
            }
        } catch (Exception e) {
            log.error(CAUGHT_EXCEPTION, e);
        }
    }

    private synchronized void loadItems(Connection connection) {
        this.roomItems.clear();

        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM items WHERE room_id = ?")) {
            statement.setInt(1, this.id);
            try (ResultSet set = statement.executeQuery()) {
                while (set.next()) {
                    this.addHabboItem(Emulator.getGameEnvironment().getItemManager().loadHabboItem(set));
                }
            }
        } catch (SQLException e) {
            log.error(CAUGHT_SQL_EXCEPTION, e);
        } catch (Exception e) {
            log.error(CAUGHT_EXCEPTION, e);
        }

        if (this.itemCount() > Room.MAXIMUM_FURNI) {
            log.error("Room ID: {} has exceeded the furniture limit ({} > {}).", this.getId(), this.itemCount(), Room.MAXIMUM_FURNI);
        }
    }

    private synchronized void loadWiredData(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, wired_data FROM items WHERE room_id = ? AND wired_data<>''")) {
            statement.setInt(1, this.id);

            try (ResultSet set = statement.executeQuery()) {
                while (set.next()) {
                    try {
                        HabboItem item = this.getHabboItem(set.getInt("id"));

                        if (item instanceof InteractionWired interactionWired) {
                            interactionWired.loadWiredData(set, this);
                        }
                    } catch (SQLException e) {
                        log.error(CAUGHT_SQL_EXCEPTION, e);
                    }
                }
            }
        } catch (SQLException e) {
            log.error(CAUGHT_SQL_EXCEPTION, e);
        } catch (Exception e) {
            log.error(CAUGHT_EXCEPTION, e);
        }
    }

    private synchronized void loadBots(Connection connection) {
        this.currentBots.clear();

        try (PreparedStatement statement = connection.prepareStatement("SELECT users.username AS owner_name, bots.* FROM bots INNER JOIN users ON bots.user_id = users.id WHERE room_id = ?")) {
            statement.setInt(1, this.id);
            try (ResultSet set = statement.executeQuery()) {
                while (set.next()) {
                    Bot b = Emulator.getGameEnvironment().getBotManager().loadBot(set);

                    if (b != null) {
                        b.setRoom(this);
                        b.setRoomUnit(new RoomUnit());
                        b.getRoomUnit().setPathFinderRoom(this);
                        b.getRoomUnit().setLocation(this.layout.getTile((short) set.getInt("x"), (short) set.getInt("y")));
                        if (b.getRoomUnit().getCurrentLocation() == null || b.getRoomUnit().getCurrentLocation().getState() == RoomTileState.INVALID) {
                            b.getRoomUnit().setZ(this.getLayout().getDoorTile().getStackHeight());
                            b.getRoomUnit().setLocation(this.getLayout().getDoorTile());
                            b.getRoomUnit().setRotation(RoomUserRotation.fromValue(this.getLayout().getDoorDirection()));
                        } else {
                            b.getRoomUnit().setZ(set.getDouble("z"));
                            b.getRoomUnit().setPreviousLocationZ(set.getDouble("z"));
                            b.getRoomUnit().setRotation(RoomUserRotation.values()[set.getInt("rot")]);
                        }
                        b.getRoomUnit().setRoomUnitType(RoomUnitType.BOT);
                        b.getRoomUnit().setDanceType(DanceType.values()[set.getInt("dance")]);
                        b.getRoomUnit().setInRoom(true);
                        this.giveEffect(b.getRoomUnit(), set.getInt("effect"), Integer.MAX_VALUE);
                        this.addBot(b);
                    }
                }
            }
        } catch (SQLException e) {
            log.error(CAUGHT_SQL_EXCEPTION, e);
        } catch (Exception e) {
            log.error(CAUGHT_EXCEPTION, e);
        }
    }

    private synchronized void loadPets(Connection connection) {

        this.currentPets.clear();

        try (PreparedStatement statement = connection.prepareStatement("SELECT users.username as pet_owner_name, users_pets.* FROM users_pets INNER JOIN users ON users_pets.user_id = users.id WHERE room_id = ?")) {
            statement.setInt(1, this.id);
            try (ResultSet set = statement.executeQuery()) {
                while (set.next()) {
                    Pet pet = PetManager.loadPet(set);
                    pet.setRoom(this);
                    pet.setRoomUnit(new RoomUnit());
                    pet.getRoomUnit().setPathFinderRoom(this);
                    pet.getRoomUnit().setLocation(this.layout.getTile((short) set.getInt("x"), (short) set.getInt("y")));
                    if (pet.getRoomUnit().getCurrentLocation() == null || pet.getRoomUnit().getCurrentLocation().getState() == RoomTileState.INVALID) {
                        pet.getRoomUnit().setZ(this.getLayout().getDoorTile().getStackHeight());
                        pet.getRoomUnit().setLocation(this.getLayout().getDoorTile());
                        pet.getRoomUnit().setRotation(RoomUserRotation.fromValue(this.getLayout().getDoorDirection()));
                    } else {
                        pet.getRoomUnit().setZ(set.getDouble("z"));
                        pet.getRoomUnit().setRotation(RoomUserRotation.values()[set.getInt("rot")]);
                    }
                    pet.getRoomUnit().setRoomUnitType(RoomUnitType.PET);
                    pet.getRoomUnit().setCanWalk(true);
                    this.addPet(pet);

                    this.getFurniOwnerNames().put(pet.getUserId(), set.getString("pet_owner_name"));

                }
            }
        } catch (SQLException e) {
            log.error(CAUGHT_SQL_EXCEPTION, e);
        } catch (Exception e) {
            log.error(CAUGHT_EXCEPTION, e);
        }
    }

    private synchronized void loadWordFilter(Connection connection) {
        this.wordFilterWords.clear();

        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM room_wordfilter WHERE room_id = ?")) {
            statement.setInt(1, this.id);
            try (ResultSet set = statement.executeQuery()) {
                while (set.next()) {
                    this.wordFilterWords.add(set.getString("word"));
                }
            }
        } catch (SQLException e) {
            log.error(CAUGHT_SQL_EXCEPTION, e);
        } catch (Exception e) {
            log.error(CAUGHT_EXCEPTION, e);
        }
    }

    public void updateTile(RoomTile tile) {
        if (tile != null) {
            tile.setStackHeight(this.getStackHeight(tile.getX(), tile.getY(), false));
            tile.setState(this.calculateTileState(tile));
        }
    }

    public void updateTiles(THashSet<RoomTile> tiles) {
        for (RoomTile tile : tiles) {
            this.tileCache.remove(tile);
            tile.setStackHeight(this.getStackHeight(tile.getX(), tile.getY(), false));
            tile.setState(this.calculateTileState(tile));
        }

        this.sendComposer(new HeightMapUpdateMessageComposer(this, tiles).compose());
    }

    private RoomTileState calculateTileState(RoomTile tile) {
        return this.calculateTileState(tile, null);
    }

    private RoomTileState calculateTileState(RoomTile tile, HabboItem exclude) {
        if (tile == null || tile.getState() == RoomTileState.INVALID)
            return RoomTileState.INVALID;

        RoomTileState result = RoomTileState.OPEN;
        THashSet<HabboItem> items = this.getItemsAt(tile);

        if (items == null) return RoomTileState.INVALID;

        HabboItem tallestItem = null;

        for (HabboItem item : items) {
            if (exclude != null && item == exclude) continue;

            if (item.getBaseItem().allowLay()) {
                return RoomTileState.LAY;
            }

            if (tallestItem != null && tallestItem.getZ() + Item.getCurrentHeight(tallestItem) > item.getZ() + Item.getCurrentHeight(item))
                continue;

            result = this.checkStateForItem(item, tile);
            tallestItem = item;
        }

        return result;
    }

    private RoomTileState checkStateForItem(HabboItem item, RoomTile tile) {
        RoomTileState result = RoomTileState.BLOCKED;

        if (item.isWalkable()) {
            result = RoomTileState.OPEN;
        } else if (item.getBaseItem().allowSit()) {
            result = RoomTileState.SIT;
        } else if (item.getBaseItem().allowLay()) {
            result = RoomTileState.LAY;
        }

        RoomTileState overriddenState = item.getOverrideTileState(tile, this);
        if (overriddenState != null) {
            result = overriddenState;
        }

        return result;
    }

    public boolean tileWalkable(RoomTile t) {
        return this.tileWalkable(t.getX(), t.getY());
    }

    public boolean tileWalkable(short x, short y) {
        boolean walkable = this.layout.tileWalkable(x, y);
        RoomTile tile = this.getLayout().getTile(x, y);

        if ((walkable && tile != null) && (tile.hasUnits() && !this.allowWalkthrough)) {
            walkable = false;
        }

        return walkable;
    }

    public void pickUpItem(HabboItem item, Habbo picker) {
        if (item == null)
            return;

        if (Emulator.getPluginManager().isRegistered(FurniturePickedUpEvent.class, true)) {
            Event furniturePickedUpEvent = new FurniturePickedUpEvent(item, picker);
            Emulator.getPluginManager().fireEvent(furniturePickedUpEvent);

            if (furniturePickedUpEvent.isCancelled())
                return;
        }

        this.removeHabboItem(item.getId());
        item.onPickUp(this);
        item.setRoomId(0);
        item.needsUpdate(true);

        if (item.getBaseItem().getType() == FurnitureType.FLOOR) {
            this.sendComposer(new RemoveFloorItemComposer(item).compose());

            THashSet<RoomTile> updatedTiles = new THashSet<>();
            Rectangle rectangle = RoomLayout.getRectangle(item.getX(), item.getY(), item.getBaseItem().getWidth(), item.getBaseItem().getLength(), item.getRotation());

            for (short x = (short) rectangle.x; x < rectangle.x + rectangle.getWidth(); x++) {
                for (short y = (short) rectangle.y; y < rectangle.y + rectangle.getHeight(); y++) {
                    double stackHeight = this.getStackHeight(x, y, false);
                    RoomTile tile = this.layout.getTile(x, y);

                    if (tile != null) {
                        tile.setStackHeight(stackHeight);
                        updatedTiles.add(tile);
                    }
                }
            }
            this.sendComposer(new HeightMapUpdateMessageComposer(this, updatedTiles).compose());
            this.updateTiles(updatedTiles);
            updatedTiles.forEach(tile -> {
                this.updateHabbosAt(tile.getX(), tile.getY());
                this.updateBotsAt(tile.getX(), tile.getY());
            });
        } else if (item.getBaseItem().getType() == FurnitureType.WALL) {
            this.sendComposer(new ItemRemoveMessageComposer(item).compose());
        }

        Habbo habbo = (picker != null && picker.getHabboInfo().getId() == item.getId() ? picker : Emulator.getGameServer().getGameClientManager().getHabbo(item.getUserId()));
        if (habbo != null) {
            habbo.getInventory().getItemsComponent().addItem(item);
            habbo.getClient().sendResponse(new UnseenItemsComposer(item));
            habbo.getClient().sendResponse(new FurniListInvalidateComposer());
        }
        Emulator.getThreading().run(item);
    }

    public void updateHabbosAt(Rectangle rectangle) {
        for (short i = (short) rectangle.x; i < rectangle.x + rectangle.width; i++) {
            for (short j = (short) rectangle.y; j < rectangle.y + rectangle.height; j++) {
                this.updateHabbosAt(i, j);
            }
        }
    }

    public void updateHabbo(Habbo habbo) {
        this.updateRoomUnit(habbo.getRoomUnit());
    }

    public void updateRoomUnit(RoomUnit roomUnit) {
        HabboItem item = this.getTopItemAt(roomUnit.getX(), roomUnit.getY());

        if ((item == null && !roomUnit.isCmdSit()) || (item != null && !item.getBaseItem().allowSit()))
            roomUnit.removeStatus(RoomUnitStatus.SIT);

        double oldZ = roomUnit.getZ();

        if (item != null) {
            if (item.getBaseItem().allowSit()) {
                roomUnit.setZ(item.getZ());
            } else {
                roomUnit.setZ(item.getZ() + Item.getCurrentHeight(item));
            }

            if (oldZ != roomUnit.getZ()) {
                this.scheduledTasks.add(() -> {
                    try {
                        item.onWalkOn(roomUnit, Room.this, null);
                    } catch (Exception ignored) {

                    }
                });
            }
        }

        this.sendComposer(new UserUpdateComposer(roomUnit).compose());
    }

    public void updateHabbosAt(short x, short y) {
        this.updateHabbosAt(x, y, this.getHabbosAt(x, y));
    }

    public void updateHabbosAt(short x, short y, List<Habbo> habbos) {
        HabboItem item = this.getTopItemAt(x, y);

        for (Habbo habbo : habbos) {

            double oldZ = habbo.getRoomUnit().getZ();
            RoomUserRotation oldRotation = habbo.getRoomUnit().getBodyRotation();
            double z = habbo.getRoomUnit().getCurrentLocation().getStackHeight();
            boolean updated = false;

            if (habbo.getRoomUnit().hasStatus(RoomUnitStatus.SIT) && ((item == null && !habbo.getRoomUnit().isCmdSit()) || (item != null && !item.getBaseItem().allowSit()))) {
                habbo.getRoomUnit().removeStatus(RoomUnitStatus.SIT);
                updated = true;
            }

            if (habbo.getRoomUnit().hasStatus(RoomUnitStatus.LAY) && ((item == null && !habbo.getRoomUnit().isCmdLay()) || (item != null && !item.getBaseItem().allowLay()))) {
                habbo.getRoomUnit().removeStatus(RoomUnitStatus.LAY);
                updated = true;
            }

            if (item != null && (item.getBaseItem().allowSit() || item.getBaseItem().allowLay())) {
                habbo.getRoomUnit().setZ(item.getZ());
                habbo.getRoomUnit().setPreviousLocationZ(item.getZ());
                habbo.getRoomUnit().setRotation(RoomUserRotation.fromValue(item.getRotation()));
            } else {
                habbo.getRoomUnit().setZ(z);
                habbo.getRoomUnit().setPreviousLocationZ(z);
            }

            if (habbo.getRoomUnit().getCurrentLocation().is(x, y) && (oldZ != z || updated || oldRotation != habbo.getRoomUnit().getBodyRotation())) {
                habbo.getRoomUnit().statusUpdate(true);
            }
        }
    }

    public void updateBotsAt(short x, short y) {
        HabboItem topItem = this.getTopItemAt(x, y);

        THashSet<RoomUnit> roomUnits = new THashSet<>();

        this.getBotsAt(this.layout.getTile(x, y)).forEach(bot -> {
            if (topItem != null) {
                if (topItem.getBaseItem().allowSit()) {
                    bot.getRoomUnit().setZ(topItem.getZ());
                    bot.getRoomUnit().setPreviousLocationZ(topItem.getZ());
                    bot.getRoomUnit().setRotation(RoomUserRotation.fromValue(topItem.getRotation()));
                } else {
                    bot.getRoomUnit().setZ(topItem.getZ() + Item.getCurrentHeight(topItem));

                    if (topItem.getBaseItem().allowLay()) {
                        bot.getRoomUnit().setStatus(RoomUnitStatus.LAY, (topItem.getZ() + topItem.getBaseItem().getHeight()) + "");
                    }
                }
            } else {
                bot.getRoomUnit().setZ(bot.getRoomUnit().getCurrentLocation().getStackHeight());
                bot.getRoomUnit().setPreviousLocationZ(bot.getRoomUnit().getCurrentLocation().getStackHeight());
            }
            roomUnits.add(bot.getRoomUnit());
        });

        if (!roomUnits.isEmpty()) {
            this.sendComposer(new UserUpdateComposer(roomUnits).compose());
        }
    }

    public void pickupPetsForHabbo(Habbo habbo) {
        THashSet<Pet> pets = new THashSet<>();

        synchronized (this.currentPets) {
            for (Pet pet : this.currentPets.valueCollection()) {
                if (pet.getUserId() == habbo.getHabboInfo().getId()) {
                    pets.add(pet);
                }
            }
        }

        for (Pet pet : pets) {
            pet.removeFromRoom();
            Emulator.getThreading().run(pet);
            habbo.getInventory().getPetsComponent().addPet(pet);
            habbo.getClient().sendResponse(new PetAddedToInventoryComposer(pet));
            this.currentPets.remove(pet.getId());
        }

    }

    public void startTrade(Habbo userOne, Habbo userTwo) {
        RoomTrade trade = new RoomTrade(userOne, userTwo, this);
        synchronized (this.activeTrades) {
            this.activeTrades.add(trade);
        }

        trade.start();
    }

    public void stopTrade(RoomTrade trade) {
        synchronized (this.activeTrades) {
            this.activeTrades.remove(trade);
        }
    }

    public RoomTrade getActiveTradeForHabbo(Habbo user) {
        synchronized (this.activeTrades) {
            for (RoomTrade trade : this.activeTrades) {
                for (RoomTradeUser habbo : trade.getRoomTradeUsers()) {
                    if (habbo.getHabbo() == user)
                        return trade;
                }
            }
        }
        return null;
    }

    public synchronized void dispose() {
        synchronized (this.loadLock) {
            if (this.preventUnloading)
                return;

            if (Emulator.getPluginManager().fireEvent(new RoomUnloadingEvent(this)).isCancelled())
                return;

            if (this.loaded) {
                try {

                    if (this.traxManager != null && !this.traxManager.disposed()) {
                        this.traxManager.dispose();
                    }

                    this.roomCycleTask.cancel(false);
                    this.scheduledTasks.clear();
                    this.scheduledComposers.clear();
                    this.loaded = false;

                    this.tileCache.clear();

                    synchronized (this.mutedHabbos) {
                        this.mutedHabbos.clear();
                    }

                    for (InteractionGameTimer timer : this.getRoomSpecialTypes().getGameTimers().values()) {
                        timer.setRunning(false);
                    }

                    for (Game game : this.games) {
                        game.dispose();
                    }
                    this.games.clear();

                    removeAllPets(ownerId);

                    synchronized (this.roomItems) {
                        TIntObjectIterator<HabboItem> iterator = this.roomItems.iterator();


                        for (int i = this.roomItems.size(); i-- > 0; ) {
                            try {
                                iterator.advance();

                                if (iterator.value().needsUpdate())
                                    iterator.value().run();
                            } catch (NoSuchElementException e) {
                                break;
                            }
                        }
                    }

                    if (this.roomSpecialTypes != null) {
                        this.roomSpecialTypes.dispose();
                    }

                    synchronized (this.roomItems) {
                        this.roomItems.clear();
                    }

                    synchronized (this.habboQueue) {
                        this.habboQueue.clear();
                    }


                    for (Habbo habbo : this.currentHabbos.values()) {
                        Emulator.getGameEnvironment().getRoomManager().leaveRoom(habbo, this);
                    }

                    this.sendComposer(new CloseConnectionMessageComposer().compose());

                    this.currentHabbos.clear();


                    TIntObjectIterator<Bot> botIterator = this.currentBots.iterator();

                    for (int i = this.currentBots.size(); i-- > 0; ) {
                        try {
                            botIterator.advance();
                            botIterator.value().needsUpdate(true);
                            Emulator.getThreading().run(botIterator.value());
                        } catch (NoSuchElementException e) {
                            log.error(CAUGHT_EXCEPTION, e);
                            break;
                        }
                    }

                    this.currentBots.clear();
                    this.currentPets.clear();
                } catch (Exception e) {
                    log.error(CAUGHT_EXCEPTION, e);
                }
            }

            try {
                this.wordQuiz = "";
                this.yesVotes = 0;
                this.noVotes = 0;
                this.updateDatabaseUserCount();
                this.preLoaded = true;
                this.layout = null;
            } catch (Exception e) {
                log.error(CAUGHT_EXCEPTION, e);
            }
        }

        Emulator.getPluginManager().fireEvent(new RoomUnloadedEvent(this));
    }

    @Override
    public int compareTo(Room o) {
        if (o.getUserCount() != this.getUserCount()) {
            return o.getCurrentHabbos().size() - this.getCurrentHabbos().size();
        }

        return this.id - o.id;
    }

    @Override
    public void serialize(ServerMessage message) {
        message.appendInt(this.id);
        message.appendString(this.name);
        if (this.isPublicRoom()) {
            message.appendInt(0);
            message.appendString("");
        } else {
            message.appendInt(this.ownerId);
            message.appendString(this.ownerName);
        }
        message.appendInt(this.state.getState());
        message.appendInt(this.getUserCount());
        message.appendInt(this.usersMax);
        message.appendString(this.description);
        message.appendInt(0);
        message.appendInt(this.score);
        message.appendInt(0);
        message.appendInt(this.category);

        String[] tags = Arrays.stream(this.tags.split(";")).filter(t -> !t.isEmpty()).toArray(String[]::new);
        message.appendInt(tags.length);
        for (String s : tags) {
            message.appendString(s);
        }

        int base = 0;

        if (this.getGuildId() > 0) {
            base = base | 2;
        }

        if (this.isPromoted()) {
            base = base | 4;
        }

        if (!this.isPublicRoom()) {
            base = base | 8;
        }


        message.appendInt(base);


        if (this.getGuildId() > 0) {
            Guild g = Emulator.getGameEnvironment().getGuildManager().getGuild(this.getGuildId());
            if (g != null) {
                message.appendInt(g.getId());
                message.appendString(g.getName());
                message.appendString(g.getBadge());
            } else {
                message.appendInt(0);
                message.appendString("");
                message.appendString("");
            }
        }

        if (this.promoted) {
            message.appendString(this.promotion.getTitle());
            message.appendString(this.promotion.getDescription());
            message.appendInt((this.promotion.getEndTimestamp() - Emulator.getIntUnixTimestamp()) / 60);
        }

    }

    @Override
    public void run() {
        synchronized (this.loadLock) {
            if (this.loaded) {
                try {
                    Emulator.getThreading().run(
                            Room.this::cycle);
                } catch (Exception e) {
                    log.error(CAUGHT_EXCEPTION, e);
                }
            }
        }

        this.save();
    }

    public void save() {
        if (this.needsUpdate) {
            try (Connection connection = Emulator.getDatabase().getDataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("UPDATE rooms SET name = ?, description = ?, password = ?, state = ?, users_max = ?, category = ?, score = ?, paper_floor = ?, paper_wall = ?, paper_landscape = ?, thickness_wall = ?, wall_height = ?, thickness_floor = ?, moodlight_data = ?, tags = ?, allow_other_pets = ?, allow_other_pets_eat = ?, allow_walkthrough = ?, allow_hidewall = ?, chat_mode = ?, chat_weight = ?, chat_speed = ?, chat_hearing_distance = ?, chat_protection =?, who_can_mute = ?, who_can_kick = ?, who_can_ban = ?, poll_id = ?, guild_id = ?, roller_speed = ?, override_model = ?, is_staff_picked = ?, promoted = ?, trade_mode = ?, move_diagonally = ?, owner_id = ?, owner_name = ?, jukebox_active = ?, hidewired = ? WHERE id = ?")) {
                statement.setString(1, this.name);
                statement.setString(2, this.description);
                statement.setString(3, this.password);
                statement.setString(4, this.state.name().toLowerCase());
                statement.setInt(5, this.usersMax);
                statement.setInt(6, this.category);
                statement.setInt(7, this.score);
                statement.setString(8, this.floorPaint);
                statement.setString(9, this.wallPaint);
                statement.setString(10, this.backgroundPaint);
                statement.setInt(11, this.wallSize);
                statement.setInt(12, this.wallHeight);
                statement.setInt(13, this.floorSize);
                StringBuilder moodLightData = new StringBuilder();

                int id = 1;
                for (RoomMoodlightData data : this.moodlightData.valueCollection()) {
                    data.setId(id);
                    moodLightData.append(data).append(";");
                    id++;
                }

                statement.setString(14, moodLightData.toString());
                statement.setString(15, this.tags);
                statement.setString(16, this.allowPets ? "1" : "0");
                statement.setString(17, this.allowPetsEat ? "1" : "0");
                statement.setString(18, this.allowWalkthrough ? "1" : "0");
                statement.setString(19, this.hideWall ? "1" : "0");
                statement.setInt(20, this.chatMode);
                statement.setInt(21, this.chatWeight);
                statement.setInt(22, this.chatSpeed);
                statement.setInt(23, this.chatDistance);
                statement.setInt(24, this.chatProtection);
                statement.setInt(25, this.muteOption);
                statement.setInt(26, this.kickOption);
                statement.setInt(27, this.banOption);
                statement.setInt(28, this.pollId);
                statement.setInt(29, this.guildId);
                statement.setInt(30, this.rollerSpeed);
                statement.setString(31, this.overrideModel ? "1" : "0");
                statement.setString(32, this.staffPromotedRoom ? "1" : "0");
                statement.setString(33, this.promoted ? "1" : "0");
                statement.setInt(34, this.tradeMode);
                statement.setString(35, this.moveDiagonally ? "1" : "0");
                statement.setInt(36, this.ownerId);
                statement.setString(37, this.ownerName);
                statement.setString(38, this.jukeboxActive ? "1" : "0");
                statement.setString(39, this.hideWired ? "1" : "0");
                statement.setInt(40, this.id);
                statement.executeUpdate();
                this.needsUpdate = false;
            } catch (SQLException e) {
                log.error(CAUGHT_SQL_EXCEPTION, e);
            }
        }
    }

    private void updateDatabaseUserCount() {
        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("UPDATE rooms SET users = ? WHERE id = ? LIMIT 1")) {
            statement.setInt(1, this.currentHabbos.size());
            statement.setInt(2, this.id);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error(CAUGHT_SQL_EXCEPTION, e);
        }
    }

    private void cycle() {
        this.cycleOdd = !this.cycleOdd;
        this.cycleTimestamp = System.currentTimeMillis();
        final boolean[] foundRightHolder = {false};


        boolean loaded;
        synchronized (this.loadLock) {
            loaded = this.loaded;
        }
        this.tileCache.clear();
        if (loaded) {
            if (!this.scheduledTasks.isEmpty()) {
                ConcurrentHashMap.KeySetView<Runnable, Boolean> tasks = this.scheduledTasks;
                this.scheduledTasks = ConcurrentHashMap.newKeySet();

                for (Runnable runnable : tasks) {
                    Emulator.getThreading().run(runnable);
                }
            }

            for (ICycleable task : this.roomSpecialTypes.getCycleTasks()) {
                task.cycle(this);
            }

            if (!this.currentHabbos.isEmpty()) {
                this.idleCycles = 0;

                THashSet<RoomUnit> updatedUnit = new THashSet<>();
                ArrayList<Habbo> toKick = new ArrayList<>();

                final Room room = this;

                final long millis = System.currentTimeMillis();

                for (Habbo habbo : this.currentHabbos.values()) {
                    if (!foundRightHolder[0]) {
                        foundRightHolder[0] = habbo.getRoomUnit().getRightsLevel() != RoomRightLevels.NONE;
                    }

                    /* Habbo doesn't remove the handitem anymore, checked on February 25 2023
                    if (habbo.getRoomUnit().getHandItem() > 0 && millis - habbo.getRoomUnit().getHandItemTimestamp() > (Room.HAND_ITEM_TIME * 1000L)) {
                        this.giveHandItem(habbo, 0);
                    }

                     */

                    if (habbo.getRoomUnit().getEffectId() > 0 && millis / 1000 > habbo.getRoomUnit().getEffectEndTimestamp()) {
                        this.giveEffect(habbo, 0, -1);
                    }

                    if (habbo.getRoomUnit().isKicked()) {
                        habbo.getRoomUnit().setKickCount(habbo.getRoomUnit().getKickCount() + 1);

                        if (habbo.getRoomUnit().getKickCount() >= 5) {
                            this.scheduledTasks.add(() -> Emulator.getGameEnvironment().getRoomManager().leaveRoom(habbo, room));
                            continue;
                        }
                    }

                    if (Emulator.getConfig().getBoolean("hotel.rooms.auto.idle")) {
                        if (!habbo.getRoomUnit().isIdle()) {
                            habbo.getRoomUnit().increaseIdleTimer();

                            if (habbo.getRoomUnit().isIdle()) {
                                boolean danceIsNone = (habbo.getRoomUnit().getDanceType() == DanceType.NONE);
                                if (danceIsNone)
                                    this.sendComposer(new SleepMessageComposer(habbo.getRoomUnit()).compose());
                                if (danceIsNone && !Emulator.getConfig().getBoolean("hotel.roomuser.idle.not_dancing.ignore.wired_idle"))
                                    WiredHandler.handle(WiredTriggerType.IDLES, habbo.getRoomUnit(), this, new Object[]{habbo});
                            }
                        } else {
                            habbo.getRoomUnit().increaseIdleTimer();

                            if (!this.isOwner(habbo) && habbo.getRoomUnit().getIdleTimer() >= Room.IDLE_CYCLES_KICK) {
                                UserExitRoomEvent event = new UserExitRoomEvent(habbo, UserExitRoomEvent.UserExitRoomReason.KICKED_IDLE);
                                Emulator.getPluginManager().fireEvent(event);

                                if (!event.isCancelled()) {
                                    toKick.add(habbo);
                                }
                            }
                        }
                    }

                    if (Emulator.getConfig().getBoolean("hotel.rooms.deco_hosting") && this.ownerId != habbo.getHabboInfo().getId()) {
                        //Check if the time already have 1 minute (120 / 2 = 60s)
                        if (habbo.getRoomUnit().getTimeInRoom() >= 120) {
                            AchievementManager.progressAchievement(this.ownerId, Emulator.getGameEnvironment().getAchievementManager().getAchievement("RoomDecoHosting"));
                            habbo.getRoomUnit().resetTimeInRoom();
                        } else {
                            habbo.getRoomUnit().increaseTimeInRoom();
                        }
                    }

                    if (habbo.getHabboStats().isMutedBubbleTracker() && habbo.getHabboStats().allowTalk()) {
                        habbo.getHabboStats().setMutedBubbleTracker(false);
                        this.sendComposer(new IgnoreResultMessageComposer(habbo, IgnoreResultMessageComposer.UNIGNORED).compose());
                    }

                    // Substract 1 from the chatCounter every odd cycle, which is every (500ms * 2).
                    if (this.cycleOdd && habbo.getHabboStats().getChatCounter().get() > 0) {
                        habbo.getHabboStats().getChatCounter().decrementAndGet();
                    }

                    if (this.cycleRoomUnit(habbo.getRoomUnit())) {
                        updatedUnit.add(habbo.getRoomUnit());
                    }
                }

                if (!toKick.isEmpty()) {
                    for (Habbo habbo : toKick) {
                        Emulator.getGameEnvironment().getRoomManager().leaveRoom(habbo, this);
                    }
                }

                if (!this.currentBots.isEmpty()) {
                    TIntObjectIterator<Bot> botIterator = this.currentBots.iterator();
                    for (int i = this.currentBots.size(); i-- > 0; ) {
                        try {
                            final Bot bot;
                            try {
                                botIterator.advance();
                                bot = botIterator.value();
                            } catch (Exception e) {
                                break;
                            }

                            if (!this.allowBotsWalk && bot.getRoomUnit().isWalking()) {
                                bot.getRoomUnit().stopWalking();
                                updatedUnit.add(bot.getRoomUnit());
                                continue;
                            }

                            botIterator.value().cycle(this.allowBotsWalk);


                            if (this.cycleRoomUnit(bot.getRoomUnit())) {
                                updatedUnit.add(bot.getRoomUnit());
                            }


                        } catch (NoSuchElementException e) {
                            log.error(CAUGHT_EXCEPTION, e);
                            break;
                        }
                    }
                }

                if (!this.currentPets.isEmpty() && this.allowBotsWalk) {
                    TIntObjectIterator<Pet> petIterator = this.currentPets.iterator();
                    for (int i = this.currentPets.size(); i-- > 0; ) {
                        try {
                            petIterator.advance();
                        } catch (NoSuchElementException e) {
                            log.error(CAUGHT_EXCEPTION, e);
                            break;
                        }

                        Pet pet = petIterator.value();
                        if (this.cycleRoomUnit(pet.getRoomUnit())) {
                            updatedUnit.add(pet.getRoomUnit());
                        }

                        pet.cycle();

                        if (pet.isPacketUpdate()) {
                            updatedUnit.add(pet.getRoomUnit());
                            pet.setPacketUpdate(false);
                        }

                        if (pet.getRoomUnit().isWalking() && pet.getRoomUnit().getPath().size() == 1 && pet.getRoomUnit().hasStatus(RoomUnitStatus.GESTURE)) {
                            pet.getRoomUnit().removeStatus(RoomUnitStatus.GESTURE);
                            updatedUnit.add(pet.getRoomUnit());
                        }
                    }
                }


                if (this.rollerSpeed != -1 && this.rollerCycle >= this.rollerSpeed) {
                    this.rollerCycle = 0;

                    THashSet<MessageComposer> messages = new THashSet<>();

                    //Find alternative for this.
                    //Reason is that tile gets updated after every roller.
                    List<Integer> rollerFurniIds = new ArrayList<>();
                    List<Integer> rolledUnitIds = new ArrayList<>();


                    this.roomSpecialTypes.getRollers().forEachValue(roller -> {

                        HabboItem newRoller = null;

                        RoomTile rollerTile = this.getLayout().getTile(roller.getX(), roller.getY());

                        if (rollerTile == null)
                            return true;

                        THashSet<HabboItem> itemsOnRoller = new THashSet<>();

                        for (HabboItem item : getItemsAt(rollerTile)) {
                            if (item.getZ() >= roller.getZ() + Item.getCurrentHeight(roller)) {
                                itemsOnRoller.add(item);
                            }
                        }

                        itemsOnRoller.remove(roller);

                        if (!rollerTile.hasUnits() && itemsOnRoller.isEmpty())
                            return true;

                        RoomTile tileInFront = Room.this.layout.getTileInFront(Room.this.layout.getTile(roller.getX(), roller.getY()), roller.getRotation());

                        if (tileInFront == null)
                            return true;

                        if (!Room.this.layout.tileExists(tileInFront.getX(), tileInFront.getY()))
                            return true;

                        if (tileInFront.getState() == RoomTileState.INVALID)
                            return true;

                        if (!tileInFront.getAllowStack() && !(tileInFront.isWalkable() || tileInFront.getState() == RoomTileState.SIT || tileInFront.getState() == RoomTileState.LAY))
                            return true;

                        if (tileInFront.hasUnits())
                            return true;

                        THashSet<HabboItem> itemsNewTile = new THashSet<>();
                        itemsNewTile.addAll(getItemsAt(tileInFront));
                        itemsNewTile.removeAll(itemsOnRoller);

                        itemsOnRoller.removeIf(item -> item.getX() != roller.getX() || item.getY() != roller.getY() || rollerFurniIds.contains(item.getId()));

                        HabboItem topItem = Room.this.getTopItemAt(tileInFront.getX(), tileInFront.getY());

                        boolean allowUsers = true;
                        boolean allowFurniture = true;
                        boolean stackContainsRoller = false;

                        for (HabboItem item : itemsNewTile) {
                            if (!(item.getBaseItem().allowWalk() || item.getBaseItem().allowSit()) && !(item instanceof InteractionGate && item.getExtradata().equals("1"))) {
                                allowUsers = false;
                            }
                            if (item instanceof InteractionRoller) {
                                newRoller = item;
                                stackContainsRoller = true;

                                if ((item.getZ() != roller.getZ() || (itemsNewTile.size() > 1 && item != topItem)) && !InteractionRoller.NO_RULES) {
                                    allowUsers = false;
                                    allowFurniture = false;
                                    continue;
                                }

                                break;
                            } else {
                                allowFurniture = false;
                            }
                        }

                        if (allowFurniture) {
                            allowFurniture = tileInFront.getAllowStack();
                        }

                        double zOffset = 0;
                        if (newRoller != null) {
                            if ((!itemsNewTile.isEmpty() && (itemsNewTile.size() > 1)) && !InteractionRoller.NO_RULES) {
                                return true;
                            }
                        } else {
                            zOffset = -Item.getCurrentHeight(roller) + tileInFront.getStackHeight() - rollerTile.getZ();
                        }

                        if (allowUsers) {
                            Event roomUserRolledEvent = null;

                            if (Emulator.getPluginManager().isRegistered(UserRolledEvent.class, true)) {
                                roomUserRolledEvent = new UserRolledEvent(null, null, null);
                            }

                            ArrayList<RoomUnit> unitsOnTile = new ArrayList<>(rollerTile.getUnits());

                            for (RoomUnit unit : rollerTile.getUnits()) {
                                if (unit.getRoomUnitType() == RoomUnitType.PET) {
                                    Pet pet = this.getPet(unit);
                                    if (pet instanceof RideablePet rideablePet && rideablePet.getRider() != null) {
                                        unitsOnTile.remove(unit);
                                    }
                                }
                            }

                            THashSet<Integer> usersRolledThisTile = new THashSet<>();

                            for (RoomUnit unit : unitsOnTile) {
                                if (rolledUnitIds.contains(unit.getId())) continue;

                                if (usersRolledThisTile.size() >= Room.ROLLERS_MAXIMUM_ROLL_AVATARS) break;

                                if (stackContainsRoller && !allowFurniture && !(topItem != null && topItem.isWalkable()))
                                    continue;

                                if (unit.hasStatus(RoomUnitStatus.MOVE))
                                    continue;

                                double newZ = unit.getZ() + zOffset;

                                if (roomUserRolledEvent != null && unit.getRoomUnitType() == RoomUnitType.USER) {
                                    roomUserRolledEvent = new UserRolledEvent(getHabbo(unit), roller, tileInFront);
                                    Emulator.getPluginManager().fireEvent(roomUserRolledEvent);

                                    if (roomUserRolledEvent.isCancelled())
                                        continue;
                                }

                                // horse riding
                                boolean isRiding = false;
                                if (unit.getRoomUnitType() == RoomUnitType.USER) {
                                    Habbo rollingHabbo = this.getHabbo(unit);
                                    if (rollingHabbo != null && rollingHabbo.getHabboInfo() != null) {
                                        RideablePet riding = rollingHabbo.getHabboInfo().getRiding();
                                        if (riding != null) {
                                            RoomUnit ridingUnit = riding.getRoomUnit();
                                            newZ = ridingUnit.getZ() + zOffset;
                                            rolledUnitIds.add(ridingUnit.getId());
                                            updatedUnit.remove(ridingUnit);
                                            messages.add(new RoomUnitOnRollerComposer(ridingUnit, roller, ridingUnit.getCurrentLocation(), ridingUnit.getZ(), tileInFront, newZ, room));
                                            isRiding = true;
                                        }
                                    }
                                }

                                usersRolledThisTile.add(unit.getId());
                                rolledUnitIds.add(unit.getId());
                                updatedUnit.remove(unit);
                                messages.add(new RoomUnitOnRollerComposer(unit, roller, unit.getCurrentLocation(), unit.getZ() + (isRiding ? 1 : 0), tileInFront, newZ + (isRiding ? 1 : 0), room));

                                if (itemsOnRoller.isEmpty()) {
                                    HabboItem item = room.getTopItemAt(tileInFront.getX(), tileInFront.getY());

                                    if (item != null && itemsNewTile.contains(item) && !itemsOnRoller.contains(item)) {
                                        Emulator.getThreading().run(() -> {
                                            if (unit.getGoalLocation() == rollerTile) {
                                                try {
                                                    item.onWalkOn(unit, room, new Object[]{rollerTile, tileInFront});
                                                } catch (Exception e) {
                                                    log.error(CAUGHT_EXCEPTION, e);
                                                }
                                            }
                                        }, this.getRollerSpeed() == 0 ? 250 : InteractionRoller.DELAY);
                                    }
                                }

                                if (unit.hasStatus(RoomUnitStatus.SIT)) {
                                    unit.setSitUpdate(true);
                                }
                            }
                        }

                        if (!messages.isEmpty()) {
                            for (MessageComposer message : messages) {
                                room.sendComposer(message.compose());
                            }
                            messages.clear();
                        }

                        if (allowFurniture || !stackContainsRoller || InteractionRoller.NO_RULES) {
                            Event furnitureRolledEvent = null;

                            if (Emulator.getPluginManager().isRegistered(FurnitureRolledEvent.class, true)) {
                                furnitureRolledEvent = new FurnitureRolledEvent(null, null, null);
                            }

                            if (newRoller == null || topItem == newRoller) {
                                List<HabboItem> sortedItems = new ArrayList<>(itemsOnRoller);
                                sortedItems.sort((o1, o2) -> Double.compare(o2.getZ(), o1.getZ()));

                                for (HabboItem item : sortedItems) {
                                    if ((item.getX() == roller.getX() && item.getY() == roller.getY() && zOffset <= 0) && (item != roller)) {
                                        if (furnitureRolledEvent != null) {
                                            furnitureRolledEvent = new FurnitureRolledEvent(item, roller, tileInFront);
                                            Emulator.getPluginManager().fireEvent(furnitureRolledEvent);

                                            if (furnitureRolledEvent.isCancelled())
                                                continue;
                                        }

                                        messages.add(new FloorItemOnRollerComposer(item, roller, tileInFront, zOffset, room));
                                        rollerFurniIds.add(item.getId());
                                    }
                                }
                            }
                        }


                        if (!messages.isEmpty()) {
                            for (MessageComposer message : messages) {
                                room.sendComposer(message.compose());
                            }
                            messages.clear();
                        }

                        return true;
                    });


                    int currentTime = (int) (this.cycleTimestamp / 1000);
                    for (HabboItem pyramid : this.roomSpecialTypes.getItemsOfType(InteractionPyramid.class)) {
                        if (pyramid instanceof InteractionPyramid interactionPyramid && interactionPyramid.getNextChange() < currentTime) {
                            interactionPyramid.change(this);
                        }
                    }
                } else {
                    this.rollerCycle++;
                }

                if (!updatedUnit.isEmpty()) {
                    this.sendComposer(new UserUpdateComposer(updatedUnit).compose());
                }

                this.traxManager.cycle();
            } else {

                if (this.idleCycles < 60)
                    this.idleCycles++;
                else
                    this.dispose();
            }
        }

        synchronized (this.habboQueue) {
            if (!this.habboQueue.isEmpty() && !foundRightHolder[0]) {
                this.habboQueue.forEachEntry((a, b) -> {
                    if (b.isOnline() && b.getHabboInfo().getRoomQueueId() == Room.this.getId()) {
                        b.getClient().sendResponse(new FlatAccessDeniedMessageComposer(""));
                    }
                    return true;
                });

                this.habboQueue.clear();
            }
        }

        if (!this.scheduledComposers.isEmpty()) {
            for (ServerMessage message : this.scheduledComposers) {
                this.sendComposer(message);
            }

            this.scheduledComposers.clear();
        }
    }


    private boolean cycleRoomUnit(RoomUnit unit) {
        boolean update = unit.needsStatusUpdate();
        if (unit.hasStatus(RoomUnitStatus.SIGN)) {
            this.sendComposer(new UserUpdateComposer(unit).compose());
            unit.removeStatus(RoomUnitStatus.SIGN);
        }

        if (unit.isWalking() && unit.getPath() != null && !unit.getPath().isEmpty()) {
            if (!unit.cycle(this)) {
                return true;
            }
        } else {
            if (unit.hasStatus(RoomUnitStatus.MOVE) && !unit.isAnimateWalk()) {
                unit.removeStatus(RoomUnitStatus.MOVE);

                update = true;
            }

            if (!unit.isWalking() && !unit.isCmdSit()) {
                RoomTile thisTile = this.getLayout().getTile(unit.getX(), unit.getY());
                HabboItem topItem = this.getTallestChair(thisTile);

                if (topItem == null || !topItem.getBaseItem().allowSit()) {
                    if (unit.hasStatus(RoomUnitStatus.SIT)) {
                        unit.removeStatus(RoomUnitStatus.SIT);
                        update = true;
                    }
                } else if (thisTile.getState() == RoomTileState.SIT && (!unit.hasStatus(RoomUnitStatus.SIT) || unit.isSitUpdate())) {
                    this.dance(unit, DanceType.NONE);
                    unit.setStatus(RoomUnitStatus.SIT, (Item.getCurrentHeight(topItem)) + "");
                    unit.setZ(topItem.getZ());
                    unit.setRotation(RoomUserRotation.values()[topItem.getRotation()]);
                    unit.setSitUpdate(false);
                    return true;
                }
            }
        }

        if (!unit.isWalking() && !unit.isCmdLay()) {
            HabboItem topItem = this.getTopItemAt(unit.getX(), unit.getY());

            if (topItem == null || !topItem.getBaseItem().allowLay()) {
                if (unit.hasStatus(RoomUnitStatus.LAY)) {
                    unit.removeStatus(RoomUnitStatus.LAY);
                    update = true;
                }
            } else {
                if (!unit.hasStatus(RoomUnitStatus.LAY)) {
                    unit.setStatus(RoomUnitStatus.LAY, Item.getCurrentHeight(topItem) + "");
                    unit.setRotation(RoomUserRotation.values()[topItem.getRotation() % 4]);

                    if (topItem.getRotation() == 0 || topItem.getRotation() == 4) {
                        unit.setLocation(this.layout.getTile(unit.getX(), topItem.getY()));
                    } else {
                        unit.setLocation(this.layout.getTile(topItem.getX(), unit.getY()));
                    }
                    update = true;
                }
            }
        }

        if (update) {
            unit.statusUpdate(false);
        }

        return update;
    }

    public void setName(String name) {
        this.name = name;

        if (this.name.length() > 50) {
            this.name = this.name.substring(0, 50);
        }

        if (this.hasGuild()) {
            Guild guild = Emulator.getGameEnvironment().getGuildManager().getGuild(this.guildId);

            if (guild != null) {
                guild.setRoomName(name);
            }
        }
    }

    public void setDescription(String description) {
        this.description = description;

        if (this.description.length() > 250) {
            this.description = this.description.substring(0, 250);
        }
    }

    public boolean hasCustomLayout() {
        return this.overrideModel;
    }

    public void setHasCustomLayout(boolean overrideModel) {
        this.overrideModel = overrideModel;
    }

    public void setPassword(String password) {
        this.password = password;

        if (this.password.length() > 20) {
            this.password = this.password.substring(0, 20);
        }
    }

    public boolean moveDiagonally() {
        return this.moveDiagonally;
    }

    public void moveDiagonally(boolean moveDiagonally) {
        this.moveDiagonally = moveDiagonally;
        this.layout.moveDiagonally(this.moveDiagonally);
        this.needsUpdate = true;
    }

    public boolean hasGuild() {
        return this.guildId != 0;
    }

    public String getGuildName() {
        if (this.hasGuild()) {
            Guild guild = Emulator.getGameEnvironment().getGuildManager().getGuild(this.guildId);

            if (guild != null) {
                return guild.getName();
            }
        }

        return "";
    }

    public void setAllowPets(boolean allowPets) {
        this.allowPets = allowPets;
        if (!allowPets) {
            removeAllPets(ownerId);
        }
    }

    public Color getBackgroundTonerColor() {
        Color color = new Color(0, 0, 0);
        TIntObjectIterator<HabboItem> iterator = this.roomItems.iterator();

        for (int i = this.roomItems.size(); i > 0; i--) {
            try {
                iterator.advance();
                HabboItem object = iterator.value();

                if (object instanceof InteractionBackgroundToner) {
                    String[] extraData = object.getExtradata().split(":");

                    if (extraData.length == 4 && extraData[0].equalsIgnoreCase("1")) {
                        return Color.getHSBColor(Integer.parseInt(extraData[1]), Integer.parseInt(extraData[2]), Integer.parseInt(extraData[3]));

                    }
                }
            } catch (Exception ignored) {
            }
        }

        return color;
    }

    public void removeAllPets() {
        removeAllPets(-1);
    }

    /**
     * Removes all pets from the room except if the owner id is excludeUserId
     *
     * @param excludeUserId Habbo id to keep pets
     */
    public void removeAllPets(int excludeUserId) {
        ArrayList<Pet> toRemovePets = new ArrayList<>();
        ArrayList<Pet> removedPets = new ArrayList<>();
        synchronized (this.currentPets) {
            for (Pet pet : this.currentPets.valueCollection()) {
                try {
                    if (pet.getUserId() != excludeUserId) {
                        toRemovePets.add(pet);
                    }

                } catch (NoSuchElementException e) {
                    log.error(CAUGHT_EXCEPTION, e);
                    break;
                }
            }
        }

        for (Pet pet : toRemovePets) {
            removedPets.add(pet);

            pet.removeFromRoom();

            Habbo habbo = Emulator.getGameEnvironment().getHabboManager().getHabbo(pet.getUserId());
            if (habbo != null) {
                habbo.getInventory().getPetsComponent().addPet(pet);
                habbo.getClient().sendResponse(new PetAddedToInventoryComposer(pet));
            }

            pet.setNeedsUpdate(true);
            pet.run();
        }

        for (Pet pet : removedPets) {
            this.currentPets.remove(pet.getId());
        }
    }

    public void setRollerSpeed(int rollerSpeed) {
        this.rollerSpeed = rollerSpeed;
        this.rollerCycle = 0;
        this.needsUpdate = true;
    }

    public String[] filterAnything() {
        return new String[]{this.getOwnerName(), this.getGuildName(), this.getDescription(), this.getPromotionDesc()};
    }

    public boolean isPromoted() {
        this.promoted = this.promotion != null && this.promotion.getEndTimestamp() > Emulator.getIntUnixTimestamp();
        this.needsUpdate = true;

        return this.promoted;
    }

    public String getPromotionDesc() {
        if (this.promotion != null) {
            return this.promotion.getDescription();
        }

        return "";
    }

    public void createPromotion(String title, String description, int category) {
        this.promoted = true;

        if (this.promotion == null) {
            this.promotion = new RoomPromotion(this, title, description, Emulator.getIntUnixTimestamp() + (120 * 60), Emulator.getIntUnixTimestamp(), category);
        } else {
            this.promotion.setTitle(title);
            this.promotion.setDescription(description);
            this.promotion.setEndTimestamp(Emulator.getIntUnixTimestamp() + (120 * 60));
            this.promotion.setCategory(category);
        }

        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("INSERT INTO room_promotions (room_id, title, description, end_timestamp, start_timestamp, category) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE title = ?, description = ?, end_timestamp = ?, category = ?")) {
            statement.setInt(1, this.id);
            statement.setString(2, title);
            statement.setString(3, description);
            statement.setInt(4, this.promotion.getEndTimestamp());
            statement.setInt(5, this.promotion.getStartTimestamp());
            statement.setInt(6, category);
            statement.setString(7, this.promotion.getTitle());
            statement.setString(8, this.promotion.getDescription());
            statement.setInt(9, this.promotion.getEndTimestamp());
            statement.setInt(10, this.promotion.getCategory());
            statement.execute();
        } catch (SQLException e) {
            log.error(CAUGHT_SQL_EXCEPTION, e);
        }

        this.needsUpdate = true;
    }

    public boolean addGame(Game game) {
        synchronized (this.games) {
            return this.games.add(game);
        }
    }

    public boolean deleteGame(Game game) {
        game.stop();
        game.dispose();
        synchronized (this.games) {
            return this.games.remove(game);
        }
    }

    public Game getGame(Class<? extends Game> gameType) {
        if (gameType == null) return null;

        synchronized (this.games) {
            for (Game game : this.games) {
                if (gameType.isInstance(game)) {
                    return game;
                }
            }
        }

        return null;
    }

    public Game getGameOrCreate(Class<? extends Game> gameType) {
        Game game = this.getGame(gameType);
        if (game == null) {
            try {
                game = gameType.getDeclaredConstructor(Room.class).newInstance(this);
                this.addGame(game);
            } catch (Exception e) {
                log.error("Error getting game " + gameType.getName(), e);
            }
        }

        return game;
    }

    public int getUserCount() {
        return this.currentHabbos.size();
    }

    public Collection<Habbo> getHabbos() {
        return this.currentHabbos.values();
    }

    public String getFurniOwnerName(int userId) {
        return this.furniOwnerNames.get(userId);
    }

    public void addToQueue(Habbo habbo) {
        synchronized (this.habboQueue) {
            this.habboQueue.put(habbo.getHabboInfo().getId(), habbo);
        }
    }

    public boolean removeFromQueue(Habbo habbo) {
        try {
            this.sendComposer(new FlatAccessibleMessageComposer(habbo.getHabboInfo().getUsername()).compose());

            synchronized (this.habboQueue) {
                return this.habboQueue.remove(habbo.getHabboInfo().getId()) != null;
            }
        } catch (Exception e) {
            log.error(CAUGHT_EXCEPTION, e);
        }

        return true;
    }

    public void addHabboItem(HabboItem item) {
        if (item == null)
            return;

        synchronized (this.roomItems) {
            try {
                this.roomItems.put(item.getId(), item);
            } catch (Exception ignored) {

            }
        }

        synchronized (this.furniOwnerCount) {
            this.furniOwnerCount.put(item.getUserId(), this.furniOwnerCount.get(item.getUserId()) + 1);
        }

        synchronized (this.furniOwnerNames) {
            if (!this.furniOwnerNames.containsKey(item.getUserId())) {
                HabboInfo habbo = HabboManager.getOfflineHabboInfo(item.getUserId());

                if (habbo != null) {
                    this.furniOwnerNames.put(item.getUserId(), habbo.getUsername());
                } else {
                    log.error("Failed to find username for item (ID: {}, UserID: {})", item.getId(), item.getUserId());
                }
            }
        }

        //TODO: Move this list
        synchronized (this.roomSpecialTypes) {
            if (item instanceof ICycleable) {
                this.roomSpecialTypes.addCycleTask((ICycleable) item);
            }

            if (item instanceof InteractionWiredTrigger interactionWiredTrigger) {
                this.roomSpecialTypes.addTrigger(interactionWiredTrigger);
            } else if (item instanceof InteractionWiredEffect interactionWiredEffect) {
                this.roomSpecialTypes.addEffect(interactionWiredEffect);
            } else if (item instanceof InteractionWiredCondition interactionWiredCondition) {
                this.roomSpecialTypes.addCondition(interactionWiredCondition);
            } else if (item instanceof InteractionWiredExtra interactionWiredExtra) {
                this.roomSpecialTypes.addExtra(interactionWiredExtra);
            } else if (item instanceof InteractionBattleBanzaiTeleporter interactionBattleBanzaiTeleporter) {
                this.roomSpecialTypes.addBanzaiTeleporter(interactionBattleBanzaiTeleporter);
            } else if (item instanceof InteractionRoller interactionRoller) {
                this.roomSpecialTypes.addRoller(interactionRoller);
            } else if (item instanceof InteractionGameScoreboard interactionGameScoreboard) {
                this.roomSpecialTypes.addGameScoreboard(interactionGameScoreboard);
            } else if (item instanceof InteractionGameGate interactionGameGate) {
                this.roomSpecialTypes.addGameGate(interactionGameGate);
            } else if (item instanceof InteractionGameTimer interactionGameTimer) {
                this.roomSpecialTypes.addGameTimer(interactionGameTimer);
            } else if (item instanceof InteractionFreezeExitTile interactionFreezeExitTile) {
                this.roomSpecialTypes.addFreezeExitTile(interactionFreezeExitTile);
            } else if (item instanceof InteractionNest interactionNest) {
                this.roomSpecialTypes.addNest(interactionNest);
            } else if (item instanceof InteractionPetDrink interactionPetDrink) {
                this.roomSpecialTypes.addPetDrink(interactionPetDrink);
            } else if (item instanceof InteractionPetFood interactionPetFood) {
                this.roomSpecialTypes.addPetFood(interactionPetFood);
            } else if (item instanceof InteractionPetToy interactionPetToy) {
                this.roomSpecialTypes.addPetToy(interactionPetToy);
            } else if (item instanceof InteractionPetTree) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionPetTrampoline) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionMoodLight) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionPyramid) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionMusicDisc) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionBattleBanzaiSphere) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionTalkingFurniture) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionWater) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionWaterItem) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionMuteArea) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionBuildArea) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionTagPole) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionTagField) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionJukeBox) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionPetBreedingNest) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionBlackHole) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionWiredHighscore) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionStickyPole) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof WiredBlob) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionTent) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionSnowboardSlope) {
                this.roomSpecialTypes.addUndefined(item);
            } else if (item instanceof InteractionFireworks) {
                this.roomSpecialTypes.addUndefined(item);
            }

        }
    }

    public HabboItem getHabboItem(int id) {
        if (this.roomItems == null || this.roomSpecialTypes == null)
            return null; // room not loaded completely

        HabboItem item;
        synchronized (this.roomItems) {
            item = this.roomItems.get(id);
        }

        if (item == null)
            item = this.roomSpecialTypes.getBanzaiTeleporter(id);

        if (item == null)
            item = this.roomSpecialTypes.getTrigger(id);

        if (item == null)
            item = this.roomSpecialTypes.getEffect(id);

        if (item == null)
            item = this.roomSpecialTypes.getCondition(id);

        if (item == null)
            item = this.roomSpecialTypes.getGameGate(id);

        if (item == null)
            item = this.roomSpecialTypes.getGameScorebord(id);

        if (item == null)
            item = this.roomSpecialTypes.getGameTimer(id);

        if (item == null)
            item = this.roomSpecialTypes.getFreezeExitTiles().get(id);

        if (item == null)
            item = this.roomSpecialTypes.getRoller(id);

        if (item == null)
            item = this.roomSpecialTypes.getNest(id);

        if (item == null)
            item = this.roomSpecialTypes.getPetDrink(id);

        if (item == null)
            item = this.roomSpecialTypes.getPetFood(id);

        return item;
    }

    void removeHabboItem(int id) {
        this.removeHabboItem(this.getHabboItem(id));
    }


    public void removeHabboItem(HabboItem item) {
        if (item != null) {

            HabboItem i;
            synchronized (this.roomItems) {
                i = this.roomItems.remove(item.getId());
            }

            if (i != null) {
                synchronized (this.furniOwnerCount) {
                    synchronized (this.furniOwnerNames) {
                        int count = this.furniOwnerCount.get(i.getUserId());

                        if (count > 1)
                            this.furniOwnerCount.put(i.getUserId(), count - 1);
                        else {
                            this.furniOwnerCount.remove(i.getUserId());
                            this.furniOwnerNames.remove(i.getUserId());
                        }
                    }
                }

                if (item instanceof ICycleable) {
                    this.roomSpecialTypes.removeCycleTask((ICycleable) item);
                }

                if (item instanceof InteractionBattleBanzaiTeleporter) {
                    this.roomSpecialTypes.removeBanzaiTeleporter((InteractionBattleBanzaiTeleporter) item);
                } else if (item instanceof InteractionWiredTrigger) {
                    this.roomSpecialTypes.removeTrigger((InteractionWiredTrigger) item);
                } else if (item instanceof InteractionWiredEffect) {
                    this.roomSpecialTypes.removeEffect((InteractionWiredEffect) item);
                } else if (item instanceof InteractionWiredCondition) {
                    this.roomSpecialTypes.removeCondition((InteractionWiredCondition) item);
                } else if (item instanceof InteractionWiredExtra) {
                    this.roomSpecialTypes.removeExtra((InteractionWiredExtra) item);
                } else if (item instanceof InteractionRoller) {
                    this.roomSpecialTypes.removeRoller((InteractionRoller) item);
                } else if (item instanceof InteractionGameScoreboard) {
                    this.roomSpecialTypes.removeScoreboard((InteractionGameScoreboard) item);
                } else if (item instanceof InteractionGameGate) {
                    this.roomSpecialTypes.removeGameGate((InteractionGameGate) item);
                } else if (item instanceof InteractionGameTimer) {
                    this.roomSpecialTypes.removeGameTimer((InteractionGameTimer) item);
                } else if (item instanceof InteractionFreezeExitTile) {
                    this.roomSpecialTypes.removeFreezeExitTile((InteractionFreezeExitTile) item);
                } else if (item instanceof InteractionNest) {
                    this.roomSpecialTypes.removeNest((InteractionNest) item);
                } else if (item instanceof InteractionPetDrink) {
                    this.roomSpecialTypes.removePetDrink((InteractionPetDrink) item);
                } else if (item instanceof InteractionPetFood) {
                    this.roomSpecialTypes.removePetFood((InteractionPetFood) item);
                } else if (item instanceof InteractionPetToy) {
                    this.roomSpecialTypes.removePetToy((InteractionPetToy) item);
                } else if (item instanceof InteractionPetTree) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionPetTrampoline) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionMoodLight) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionPyramid) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionMusicDisc) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionBattleBanzaiSphere) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionTalkingFurniture) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionWaterItem) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionWater) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionMuteArea) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionTagPole) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionTagField) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionJukeBox) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionPetBreedingNest) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionBlackHole) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionWiredHighscore) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionStickyPole) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof WiredBlob) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionTent) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionSnowboardSlope) {
                    this.roomSpecialTypes.removeUndefined(item);
                } else if (item instanceof InteractionBuildArea) {
                    this.roomSpecialTypes.removeUndefined(item);
                }
            }
        }
    }

    public List<HabboItem> getFloorItems() {
        return roomItems.valueCollection().stream().filter(i -> i.getBaseItem().getType() == FurnitureType.FLOOR).toList();
    }

    public List<HabboItem> getWallItems() {
        return roomItems.valueCollection().stream().filter(i -> i.getBaseItem().getType() == FurnitureType.WALL).toList();
    }

    public List<HabboItem> getPostItNotes() {
        return roomItems.valueCollection().stream().filter(i -> i.getBaseItem().getInteractionType().getType() == InteractionPostIt.class).toList();
    }

    public void addHabbo(Habbo habbo) {
        synchronized (this.roomUnitLock) {
            habbo.getRoomUnit().setId(this.unitCounter);
            this.currentHabbos.put(habbo.getHabboInfo().getId(), habbo);
            this.unitCounter++;
            this.updateDatabaseUserCount();
        }
    }

    public void kickHabbo(Habbo habbo, boolean alert) {
        if (alert) {
            habbo.getClient().sendResponse(new GenericErrorComposer(GenericErrorComposer.KICKED_OUT_OF_THE_ROOM));
        }

        habbo.getRoomUnit().setKicked(true);
        habbo.getRoomUnit().setGoalLocation(this.layout.getDoorTile());

        if (habbo.getRoomUnit().getPath() == null || habbo.getRoomUnit().getPath().size() <= 1 || this.isPublicRoom()) {
            habbo.getRoomUnit().setCanWalk(true);
            Emulator.getGameEnvironment().getRoomManager().leaveRoom(habbo, this);
        }
    }

    public void removeHabbo(Habbo habbo) {
        removeHabbo(habbo, false);
    }

    public void removeHabbo(Habbo habbo, boolean sendRemovePacket) {
        if (habbo.getRoomUnit() != null && habbo.getRoomUnit().getCurrentLocation() != null) {
            habbo.getRoomUnit().getCurrentLocation().removeUnit(habbo.getRoomUnit());
        }

        synchronized (this.roomUnitLock) {
            this.currentHabbos.remove(habbo.getHabboInfo().getId());
        }

        if (sendRemovePacket && habbo.getRoomUnit() != null && !habbo.getRoomUnit().isTeleporting()) {
            this.sendComposer(new UserRemoveMessageComposer(habbo.getRoomUnit()).compose());
        }

        if (habbo.getRoomUnit().getCurrentLocation() != null) {
            HabboItem item = this.getTopItemAt(habbo.getRoomUnit().getX(), habbo.getRoomUnit().getY());

            if (item != null) {
                try {
                    item.onWalkOff(habbo.getRoomUnit(), this, new Object[]{});
                } catch (Exception e) {
                    log.error(CAUGHT_EXCEPTION, e);
                }
            }
        }

        if (habbo.getHabboInfo().getCurrentGame() != null && this.getGame(habbo.getHabboInfo().getCurrentGame()) != null) {
            this.getGame(habbo.getHabboInfo().getCurrentGame()).removeHabbo(habbo);
        }

        RoomTrade trade = this.getActiveTradeForHabbo(habbo);

        if (trade != null) {
            trade.stopTrade(habbo);
        }

        if (habbo.getHabboInfo().getId() != this.ownerId) {
            this.pickupPetsForHabbo(habbo);
        }

        this.updateDatabaseUserCount();
    }

    public void addBot(Bot bot) {
        synchronized (this.roomUnitLock) {
            bot.getRoomUnit().setId(this.unitCounter);
            this.currentBots.put(bot.getId(), bot);
            this.unitCounter++;
        }
    }

    public void addPet(Pet pet) {
        synchronized (this.roomUnitLock) {
            pet.getRoomUnit().setId(this.unitCounter);
            this.currentPets.put(pet.getId(), pet);
            this.unitCounter++;

            Habbo habbo = this.getHabbo(pet.getUserId());
            if (habbo != null) {
                this.furniOwnerNames.put(pet.getUserId(), this.getHabbo(pet.getUserId()).getHabboInfo().getUsername());
            }
        }
    }

    public Bot getBot(int botId) {
        return this.currentBots.get(botId);
    }

    public Bot getBot(RoomUnit roomUnit) {
        return getBot(roomUnit.getId());
    }

    public Bot getBotByRoomUnitId(int id) {
        synchronized (this.currentBots) {
            return currentBots.valueCollection().stream().filter(b -> b.getRoomUnit().getId() == id).findFirst().orElse(null);
        }
    }

    public List<Bot> getBots(String name) {
        synchronized (this.currentBots) {
            return currentBots.valueCollection().stream().filter(b -> b.getName().equalsIgnoreCase(name)).toList();

        }
    }

    public boolean hasBotsAt(final int x, final int y) {
        final boolean[] result = {false};

        synchronized (this.currentBots) {
            this.currentBots.forEachValue(object -> {
                if (object.getRoomUnit().getX() == x && object.getRoomUnit().getY() == y) {
                    result[0] = true;
                    return false;
                }

                return true;
            });
        }

        return result[0];
    }

    public Pet getPet(int petId) {
        return this.currentPets.get(petId);
    }

    public Pet getPet(RoomUnit roomUnit) {
        return currentPets.valueCollection().stream().filter(p -> p.getRoomUnit() == roomUnit).findFirst().orElse(null);
    }

    public boolean removeBot(Bot bot) {
        synchronized (this.currentBots) {
            if (this.currentBots.containsKey(bot.getId())) {
                if (bot.getRoomUnit() != null && bot.getRoomUnit().getCurrentLocation() != null) {
                    bot.getRoomUnit().getCurrentLocation().removeUnit(bot.getRoomUnit());
                }

                this.currentBots.remove(bot.getId());
                bot.getRoomUnit().setInRoom(false);
                bot.setRoom(null);
                this.sendComposer(new UserRemoveMessageComposer(bot.getRoomUnit()).compose());
                bot.setRoomUnit(null);
                return true;
            }
        }

        return false;
    }

    public void placePet(Pet pet, short x, short y, double z) {
        synchronized (this.currentPets) {
            RoomTile tile = this.layout.getTile(x, y);

            if (tile == null) {
                tile = this.layout.getDoorTile();
            }

            pet.setRoomUnit(new RoomUnit());
            pet.setRoom(this);
            pet.getRoomUnit()
                    .setGoalLocation(tile)
                    .setLocation(tile)
                    .setRoomUnitType(RoomUnitType.PET)
                    .setCanWalk(true)
                    .setPathFinderRoom(this)
                    .setPreviousLocationZ(z)
                    .setZ(z);

            if (pet.getRoomUnit().getCurrentLocation() == null) {
                pet.getRoomUnit()
                        .setLocation(this.getLayout().getDoorTile())
                        .setRotation(RoomUserRotation.fromValue(this.getLayout().getDoorDirection()));
            }

            pet.setNeedsUpdate(true);
            this.furniOwnerNames.put(pet.getUserId(), this.getHabbo(pet.getUserId()).getHabboInfo().getUsername());
            this.addPet(pet);
            this.sendComposer(new RoomPetComposer(pet).compose());
        }
    }

    public Pet removePet(int petId) {
        return this.currentPets.remove(petId);
    }

    public boolean hasHabbosAt(int x, int y) {
        return getHabbos().stream().anyMatch(h -> h.getRoomUnit().getX() == x && h.getRoomUnit().getY() == y);
    }

    public boolean hasPetsAt(int x, int y) {
        synchronized (this.currentPets) {
            return currentPets.valueCollection().stream().anyMatch(p -> p.getRoomUnit().getX() == x && p.getRoomUnit().getY() == y);
        }
    }

    public List<Pet> getPetsAt(RoomTile tile) {
        synchronized (this.currentPets) {
            return currentPets.valueCollection().stream().filter(p -> p.getRoomUnit().getCurrentLocation().equals(tile)).toList();
        }
    }

    public List<Bot> getBotsAt(RoomTile tile) {
        synchronized (this.currentBots) {
            return currentBots.valueCollection().stream().filter(b -> b.getRoomUnit().getCurrentLocation().equals(tile)).toList();
        }
    }

    public List<Habbo> getHabbosAt(short x, short y) {
        return this.getHabbosAt(this.layout.getTile(x, y));
    }

    public List<Habbo> getHabbosAt(RoomTile tile) {
        return getHabbos().stream().filter(h -> h.getRoomUnit().getCurrentLocation().equals(tile)).toList();
    }

    public THashSet<RoomUnit> getHabbosAndBotsAt(short x, short y) {
        return this.getHabbosAndBotsAt(this.layout.getTile(x, y));
    }

    public THashSet<RoomUnit> getHabbosAndBotsAt(RoomTile tile) {
        THashSet<RoomUnit> list = new THashSet<>();
        list.addAll(getBotsAt(tile).stream().map(Bot::getRoomUnit).toList());
        list.addAll(getHabbosAt(tile).stream().map(Habbo::getRoomUnit).toList());

        return list;
    }

    public THashSet<Habbo> getHabbosOnItem(HabboItem item) {
        THashSet<Habbo> habbos = new THashSet<>();
        for (short x = item.getX(); x < item.getX() + item.getBaseItem().getLength(); x++) {
            for (short y = item.getY(); y < item.getY() + item.getBaseItem().getWidth(); y++) {
                habbos.addAll(this.getHabbosAt(x, y));
            }
        }

        return habbos;
    }

    public THashSet<Bot> getBotsOnItem(HabboItem item) {
        THashSet<Bot> bots = new THashSet<>();
        for (short x = item.getX(); x < item.getX() + item.getBaseItem().getLength(); x++) {
            for (short y = item.getY(); y < item.getY() + item.getBaseItem().getWidth(); y++) {
                bots.addAll(this.getBotsAt(this.getLayout().getTile(x, y)));
            }
        }

        return bots;
    }

    public THashSet<Pet> getPetsOnItem(HabboItem item) {
        THashSet<Pet> pets = new THashSet<>();
        for (short x = item.getX(); x < item.getX() + item.getBaseItem().getLength(); x++) {
            for (short y = item.getY(); y < item.getY() + item.getBaseItem().getWidth(); y++) {
                pets.addAll(this.getPetsAt(this.getLayout().getTile(x, y)));
            }
        }

        return pets;
    }

    public void teleportHabboToItem(Habbo habbo, HabboItem item) {
        this.teleportRoomUnitToLocation(habbo.getRoomUnit(), item.getX(), item.getY(), item.getZ() + Item.getCurrentHeight(item));
    }

    public void teleportHabboToLocation(Habbo habbo, short x, short y) {
        this.teleportRoomUnitToLocation(habbo.getRoomUnit(), x, y, 0.0);
    }

    public void teleportRoomUnitToItem(RoomUnit roomUnit, HabboItem item) {
        this.teleportRoomUnitToLocation(roomUnit, item.getX(), item.getY(), item.getZ() + Item.getCurrentHeight(item));
    }

    public void teleportRoomUnitToLocation(RoomUnit roomUnit, short x, short y) {
        this.teleportRoomUnitToLocation(roomUnit, x, y, 0.0);
    }

    public void teleportRoomUnitToLocation(RoomUnit roomUnit, short x, short y, double z) {
        if (this.loaded) {
            RoomTile tile = this.layout.getTile(x, y);

            if (z < tile.getZ()) {
                z = tile.getZ();
            }

            roomUnit.setLocation(tile);
            roomUnit.setGoalLocation(tile);
            roomUnit.setZ(z);
            roomUnit.setPreviousLocationZ(z);
            this.updateRoomUnit(roomUnit);


        }
    }

    public void muteHabbo(Habbo habbo, int minutes) {
        synchronized (this.mutedHabbos) {
            this.mutedHabbos.put(habbo.getHabboInfo().getId(), Emulator.getIntUnixTimestamp() + (minutes * 60));
        }
    }

    public boolean isMuted(Habbo habbo) {
        if (this.isOwner(habbo) || this.hasRights(habbo))
            return false;

        if (this.mutedHabbos.containsKey(habbo.getHabboInfo().getId())) {
            boolean time = this.mutedHabbos.get(habbo.getHabboInfo().getId()) > Emulator.getIntUnixTimestamp();

            if (!time) {
                this.mutedHabbos.remove(habbo.getHabboInfo().getId());
            }

            return time;
        }

        return false;
    }

    public void habboEntered(Habbo habbo) {
        habbo.getRoomUnit().setAnimateWalk(false);

        synchronized (this.currentBots) {
            if (habbo.getHabboInfo().getId() != this.getOwnerId())
                return;

            TIntObjectIterator<Bot> botIterator = this.currentBots.iterator();

            for (int i = this.currentBots.size(); i-- > 0; ) {
                try {
                    botIterator.advance();

                    if (botIterator.value() instanceof VisitorBot visitorBot) {
                        visitorBot.onUserEnter(habbo);
                        break;
                    }
                } catch (Exception e) {
                    break;
                }
            }
        }

        HabboItem doorTileTopItem = this.getTopItemAt(habbo.getRoomUnit().getX(), habbo.getRoomUnit().getY());
        if (doorTileTopItem != null && !(doorTileTopItem instanceof InteractionTeleportTile)) {
            try {
                doorTileTopItem.onWalkOn(habbo.getRoomUnit(), this, new Object[]{});
            } catch (Exception e) {
                log.error(CAUGHT_EXCEPTION, e);
            }
        }
    }

    public void floodMuteHabbo(Habbo habbo, int timeOut) {
        habbo.getHabboStats().setMutedCount(habbo.getHabboStats().getMutedCount() + 1);
        timeOut += (timeOut * (int) Math.ceil(Math.pow(habbo.getHabboStats().getMutedCount(), 2)));
        habbo.getHabboStats().getChatCounter().set(0);
        habbo.mute(timeOut, true);
    }

    public void talk(Habbo habbo, RoomChatMessage roomChatMessage, RoomChatType chatType) {
        this.talk(habbo, roomChatMessage, chatType, false);
    }

    public void talk(final Habbo habbo, final RoomChatMessage roomChatMessage, RoomChatType chatType, boolean ignoreWired) {
        if (!habbo.getHabboStats().allowTalk())
            return;

        if (habbo.getRoomUnit().isInvisible() && Emulator.getConfig().getBoolean("invisible.prevent.chat", false)) {
            if (!Emulator.getGameEnvironment().getCommandsManager().handleCommand(habbo.getClient(), roomChatMessage.getUnfilteredMessage())) {
                habbo.whisper(Emulator.getTexts().getValue("invisible.prevent.chat.error"));
            }

            return;
        }

        if (habbo.getHabboInfo().getCurrentRoom() != this)
            return;

        long millis = System.currentTimeMillis();
        if (HABBO_CHAT_DELAY && millis - habbo.getHabboStats().getLastChat() < 750) {
            return;
        }

        habbo.getHabboStats().setLastChat(millis);
        if (roomChatMessage != null && Emulator.getConfig().getBoolean("easter_eggs.enabled") && roomChatMessage.getMessage().equalsIgnoreCase("i am a pirate")) {
            habbo.getHabboStats().getChatCounter().addAndGet(1);
            Emulator.getThreading().run(new YouAreAPirate(habbo, this));
            return;
        }

        UserIdleEvent event = new UserIdleEvent(habbo, UserIdleEvent.IdleReason.TALKED, false);
        Emulator.getPluginManager().fireEvent(event);

        if (!event.isCancelled() && !event.isIdle()) {
            this.unIdle(habbo);
        }

        this.sendComposer(new UserTypingMessageComposer(habbo.getRoomUnit(), false).compose());

        if (roomChatMessage == null || roomChatMessage.getMessage() == null || roomChatMessage.getMessage().equals(""))
            return;

        if (!habbo.hasRight(Permission.ACC_NOMUTE) && (!MUTEAREA_CAN_WHISPER || chatType != RoomChatType.WHISPER)) {
            for (HabboItem area : this.getRoomSpecialTypes().getItemsOfType(InteractionMuteArea.class)) {
                if (((InteractionMuteArea) area).inSquare(habbo.getRoomUnit().getCurrentLocation())) {
                    return;
                }
            }
        }

        if (!this.wordFilterWords.isEmpty() && !habbo.hasRight(Permission.ACC_CHAT_NO_FILTER)) {
            for (String string : this.wordFilterWords) {
                roomChatMessage.setMessage(roomChatMessage.getMessage().replaceAll("(?i)" + Pattern.quote(string), "bobba"));
            }
        }

        if (!habbo.hasRight(Permission.ACC_NOMUTE)) {
            if (this.isMuted() && !this.hasRights(habbo)) {
                return;
            }

            if (this.isMuted(habbo)) {
                habbo.getClient().sendResponse(new RemainingMutePeriodComposer(this.mutedHabbos.get(habbo.getHabboInfo().getId()) - Emulator.getIntUnixTimestamp()));
                return;
            }
        }

        if (chatType != RoomChatType.WHISPER) {
            if (Emulator.getGameEnvironment().getCommandsManager().handleCommand(habbo.getClient(), roomChatMessage.getUnfilteredMessage())) {
                WiredHandler.handle(WiredTriggerType.SAY_COMMAND, habbo.getRoomUnit(), habbo.getHabboInfo().getCurrentRoom(), new Object[]{roomChatMessage.getMessage()});
                roomChatMessage.isCommand = true;
                return;
            }

            if (!ignoreWired) {
                if (WiredHandler.handle(WiredTriggerType.SAY_SOMETHING, habbo.getRoomUnit(), habbo.getHabboInfo().getCurrentRoom(), new Object[]{roomChatMessage.getMessage()})) {
                    habbo.getClient().sendResponse(new WhisperMessageComposer(new RoomChatMessage(roomChatMessage.getMessage(), habbo, habbo, roomChatMessage.getBubble())));
                    return;
                }
            }
        }

        if (!habbo.hasRight(Permission.ACC_CHAT_NO_FLOOD)) {
            final int chatCounter = habbo.getHabboStats().getChatCounter().addAndGet(1);

            if (chatCounter > 3) {
                final boolean floodRights = Emulator.getConfig().getBoolean("flood.with.rights");
                final boolean hasRights = this.hasRights(habbo);

                if (floodRights || !hasRights) {
                    if (this.chatProtection == 0 || (this.chatProtection == 1 && chatCounter > 4) || (this.chatProtection == 2 && chatCounter > 5)) {
                        this.floodMuteHabbo(habbo, muteTime);
                        return;
                    }
                }
            }
        }

        ServerMessage prefixMessage = null;

        if (Emulator.getPluginManager().isRegistered(UsernameTalkEvent.class, true)) {
            UsernameTalkEvent usernameTalkEvent = Emulator.getPluginManager().fireEvent(new UsernameTalkEvent(habbo, roomChatMessage, chatType));
            if (usernameTalkEvent.hasCustomComposer()) {
                prefixMessage = usernameTalkEvent.getCustomComposer();
            }
        }

        if (prefixMessage == null) {
            prefixMessage = roomChatMessage.getHabbo().getHabboInfo().getPermissionGroup().hasPrefix() ? new UserNameChangedMessageComposer(habbo, true).compose() : null;
        }
        ServerMessage clearPrefixMessage = prefixMessage != null ? new UserNameChangedMessageComposer(habbo).compose() : null;

        Rectangle tentRectangle = this.roomSpecialTypes.tentAt(habbo.getRoomUnit().getCurrentLocation());

        String trimmedMessage = roomChatMessage.getMessage().replaceAll("\\s+$", "");

        if (trimmedMessage.isEmpty()) trimmedMessage = " ";

        roomChatMessage.setMessage(trimmedMessage);

        if (chatType == RoomChatType.WHISPER) {
            if (roomChatMessage.getTargetHabbo() == null) {
                return;
            }

            RoomChatMessage staffChatMessage = new RoomChatMessage(roomChatMessage);
            staffChatMessage.setMessage("To " + staffChatMessage.getTargetHabbo().getHabboInfo().getUsername() + ": " + staffChatMessage.getMessage());

            final ServerMessage message = new WhisperMessageComposer(roomChatMessage).compose();
            final ServerMessage staffMessage = new WhisperMessageComposer(staffChatMessage).compose();

            for (Habbo h : this.getHabbos()) {
                if (h == roomChatMessage.getTargetHabbo() || h == habbo) {
                    if (!h.getHabboStats().userIgnored(habbo.getHabboInfo().getId())) {
                        if (prefixMessage != null) {
                            h.getClient().sendResponse(prefixMessage);
                        }
                        h.getClient().sendResponse(message);

                        if (clearPrefixMessage != null) {
                            h.getClient().sendResponse(clearPrefixMessage);
                        }
                    }

                    continue;
                }
                if (h.hasRight(Permission.ACC_SEE_WHISPERS)) {
                    h.getClient().sendResponse(staffMessage);
                }
            }
        } else if (chatType == RoomChatType.TALK) {
            ServerMessage message = new ChatMessageComposer(roomChatMessage).compose();
            boolean noChatLimit = habbo.hasRight(Permission.ACC_CHAT_NO_LIMIT);

            for (Habbo h : this.getHabbos()) {
                if ((h.getRoomUnit().getCurrentLocation().distance(habbo.getRoomUnit().getCurrentLocation()) <= this.chatDistance ||
                        h.equals(habbo) ||
                        this.hasRights(h) ||
                        noChatLimit) && (tentRectangle == null || RoomLayout.tileInSquare(tentRectangle, h.getRoomUnit().getCurrentLocation()))) {
                    if (!h.getHabboStats().userIgnored(habbo.getHabboInfo().getId())) {
                        if (prefixMessage != null && !h.getHabboStats().isPreferOldChat()) {
                            h.getClient().sendResponse(prefixMessage);
                        }
                        h.getClient().sendResponse(message);
                        if (clearPrefixMessage != null && !h.getHabboStats().isPreferOldChat()) {
                            h.getClient().sendResponse(clearPrefixMessage);
                        }
                    }
                    continue;
                }
                // Staff should be able to see the tent chat anyhow
                showTentChatMessageOutsideTentIfPermitted(h, roomChatMessage, tentRectangle);
            }
        } else if (chatType == RoomChatType.SHOUT) {
            ServerMessage message = new ShoutMessageComposer(roomChatMessage).compose();

            for (Habbo h : this.getHabbos()) {
                // Show the message
                // If the receiving Habbo has not ignored the sending Habbo
                // AND the sending Habbo is NOT in a tent OR the receiving Habbo is in the same tent as the sending Habbo
                if (!h.getHabboStats().userIgnored(habbo.getHabboInfo().getId()) && (tentRectangle == null || RoomLayout.tileInSquare(tentRectangle, h.getRoomUnit().getCurrentLocation()))) {
                    if (prefixMessage != null && !h.getHabboStats().isPreferOldChat()) {
                        h.getClient().sendResponse(prefixMessage);
                    }
                    h.getClient().sendResponse(message);
                    if (clearPrefixMessage != null && !h.getHabboStats().isPreferOldChat()) {
                        h.getClient().sendResponse(clearPrefixMessage);
                    }
                    continue;
                }
                // Staff should be able to see the tent chat anyhow, even when not in the same tent
                showTentChatMessageOutsideTentIfPermitted(h, roomChatMessage, tentRectangle);
            }
        }

        if (chatType == RoomChatType.TALK || chatType == RoomChatType.SHOUT) {
            synchronized (this.currentBots) {
                TIntObjectIterator<Bot> botIterator = this.currentBots.iterator();

                for (int i = this.currentBots.size(); i-- > 0; ) {
                    try {
                        botIterator.advance();
                        Bot bot = botIterator.value();
                        bot.onUserSay(roomChatMessage);

                    } catch (NoSuchElementException e) {
                        log.error(CAUGHT_EXCEPTION, e);
                        break;
                    }
                }
            }

            if (roomChatMessage.getBubble().triggersTalkingFurniture()) {
                THashSet<HabboItem> items = this.roomSpecialTypes.getItemsOfType(InteractionTalkingFurniture.class);

                for (HabboItem item : items) {
                    if (this.layout.getTile(item.getX(), item.getY()).distance(habbo.getRoomUnit().getCurrentLocation()) <= Emulator.getConfig().getInt("furniture.talking.range")) {
                        int count = Emulator.getConfig().getInt(item.getBaseItem().getName() + ".message.count", 0);

                        if (count > 0) {
                            int randomValue = Emulator.getRandom().nextInt(count + 1);

                            RoomChatMessage itemMessage = new RoomChatMessage(Emulator.getTexts().getValue(item.getBaseItem().getName() + ".message." + randomValue, item.getBaseItem().getName() + ".message." + randomValue + " not found!"), habbo, RoomChatMessageBubbles.getBubble(Emulator.getConfig().getInt(item.getBaseItem().getName() + ".message.bubble", RoomChatMessageBubbles.PARROT.getType())));

                            this.sendComposer(new ChatMessageComposer(itemMessage).compose());

                            try {
                                item.onClick(habbo.getClient(), this, new Object[0]);
                                item.setExtradata("1");
                                updateItemState(item);

                                Emulator.getThreading().run(() -> {
                                    item.setExtradata("0");
                                    updateItemState(item);
                                }, 2000);

                                break;
                            } catch (Exception e) {
                                log.error(CAUGHT_EXCEPTION, e);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Sends the given message to the receiving Habbo if the Habbo has the ACC_SEE_TENTCHAT permission and is not within the tent
     *
     * @param receivingHabbo  The receiving Habbo
     * @param roomChatMessage The message to receive
     * @param tentRectangle   The whole tent area from where the sending Habbo is saying something
     */
    private void showTentChatMessageOutsideTentIfPermitted(Habbo receivingHabbo, RoomChatMessage roomChatMessage, Rectangle tentRectangle) {
        if (receivingHabbo != null && receivingHabbo.hasRight(Permission.ACC_SEE_TENTCHAT) && tentRectangle != null && !RoomLayout.tileInSquare(tentRectangle, receivingHabbo.getRoomUnit().getCurrentLocation())) {
            RoomChatMessage staffChatMessage = new RoomChatMessage(roomChatMessage);
            staffChatMessage.setMessage("[" + Emulator.getTexts().getValue("hotel.room.tent.prefix") + "] " + staffChatMessage.getMessage());
            final ServerMessage staffMessage = new WhisperMessageComposer(staffChatMessage).compose();
            receivingHabbo.getClient().sendResponse(staffMessage);
        }
    }

    public THashSet<RoomTile> getLockedTiles() {
        THashSet<RoomTile> lockedTiles = new THashSet<>();

        TIntObjectIterator<HabboItem> iterator = this.roomItems.iterator();


        for (int i = this.roomItems.size(); i-- > 0; ) {
            HabboItem item;
            try {
                iterator.advance();
                item = iterator.value();
            } catch (Exception e) {
                break;
            }

            if (item.getBaseItem().getType() != FurnitureType.FLOOR)
                continue;

            boolean found = lockedTiles.stream().anyMatch(tile -> tile.getX() == item.getX() && tile.getY() == item.getY());

            if (!found) {
                if (item.getRotation() == 0 || item.getRotation() == 4) {
                    for (short y = 0; y < item.getBaseItem().getLength(); y++) {
                        for (short x = 0; x < item.getBaseItem().getWidth(); x++) {
                            RoomTile tile = this.layout.getTile((short) (item.getX() + x), (short) (item.getY() + y));

                            if (tile != null) {
                                lockedTiles.add(tile);
                            }
                        }
                    }
                } else {
                    for (short y = 0; y < item.getBaseItem().getWidth(); y++) {
                        for (short x = 0; x < item.getBaseItem().getLength(); x++) {
                            RoomTile tile = this.layout.getTile((short) (item.getX() + x), (short) (item.getY() + y));

                            if (tile != null) {
                                lockedTiles.add(tile);
                            }
                        }
                    }
                }
            }
        }

        return lockedTiles;
    }

    @Deprecated
    public THashSet<HabboItem> getItemsAt(int x, int y) {
        RoomTile tile = this.getLayout().getTile((short) x, (short) y);

        if (tile != null) {
            return this.getItemsAt(tile);
        }

        return new THashSet<>(0);
    }

    public THashSet<HabboItem> getItemsAt(RoomTile tile) {
        return getItemsAt(tile, false);
    }

    public THashSet<HabboItem> getItemsAt(RoomTile tile, boolean returnOnFirst) {
        THashSet<HabboItem> items = new THashSet<>(0);

        if (tile == null)
            return items;

        if (this.loaded) {
            THashSet<HabboItem> cachedItems = this.tileCache.get(tile);
            if (cachedItems != null)
                return cachedItems;
        }

        TIntObjectIterator<HabboItem> iterator = this.roomItems.iterator();

        for (int i = this.roomItems.size(); i-- > 0; ) {
            HabboItem item;
            try {
                iterator.advance();
                item = iterator.value();
            } catch (Exception e) {
                break;
            }

            if (item == null)
                continue;

            if (item.getBaseItem().getType() != FurnitureType.FLOOR)
                continue;

            int width, length;

            if (item.getRotation() != 2 && item.getRotation() != 6) {
                width = item.getBaseItem().getWidth() > 0 ? item.getBaseItem().getWidth() : 1;
                length = item.getBaseItem().getLength() > 0 ? item.getBaseItem().getLength() : 1;
            } else {
                width = item.getBaseItem().getLength() > 0 ? item.getBaseItem().getLength() : 1;
                length = item.getBaseItem().getWidth() > 0 ? item.getBaseItem().getWidth() : 1;
            }

            if (!(tile.getX() >= item.getX() && tile.getX() <= item.getX() + width - 1 && tile.getY() >= item.getY() && tile.getY() <= item.getY() + length - 1))
                continue;

            items.add(item);

            if (returnOnFirst) {
                return items;
            }
        }

        if (this.loaded) {
            this.tileCache.put(tile, items);
        }

        return items;
    }

    public THashSet<HabboItem> getItemsAt(int x, int y, double minZ) {
        THashSet<HabboItem> items = new THashSet<>();

        for (HabboItem item : this.getItemsAt(x, y)) {
            if (item.getZ() < minZ)
                continue;

            items.add(item);
        }
        return items;
    }

    public THashSet<HabboItem> getItemsAt(Class<? extends HabboItem> type, int x, int y) {
        THashSet<HabboItem> items = new THashSet<>();

        for (HabboItem item : this.getItemsAt(x, y)) {
            if (!item.getClass().equals(type))
                continue;

            items.add(item);
        }
        return items;
    }

    public boolean hasItemsAt(int x, int y) {
        RoomTile tile = this.getLayout().getTile((short) x, (short) y);

        if (tile == null)
            return false;

        return !this.getItemsAt(tile, true).isEmpty();
    }

    public HabboItem getTopItemAt(int x, int y) {
        return this.getTopItemAt(x, y, null);
    }

    public HabboItem getTopItemAt(int x, int y, HabboItem exclude) {
        RoomTile tile = this.getLayout().getTile((short) x, (short) y);

        if (tile == null)
            return null;

        HabboItem highestItem = null;

        for (HabboItem item : this.getItemsAt(x, y)) {
            if (exclude != null && exclude == item)
                continue;

            if (highestItem != null && highestItem.getZ() + Item.getCurrentHeight(highestItem) > item.getZ() + Item.getCurrentHeight(item))
                continue;

            highestItem = item;
        }

        return highestItem;
    }

    public HabboItem getTopItemAt(THashSet<RoomTile> tiles, HabboItem exclude) {
        HabboItem highestItem = null;
        for (RoomTile tile : tiles) {

            if (tile == null)
                continue;

            for (HabboItem item : this.getItemsAt(tile.getX(), tile.getY())) {
                if (exclude != null && exclude == item)
                    continue;

                if (highestItem != null && highestItem.getZ() + Item.getCurrentHeight(highestItem) > item.getZ() + Item.getCurrentHeight(item))
                    continue;

                highestItem = item;
            }
        }

        return highestItem;
    }

    public double getTopHeightAt(int x, int y) {
        HabboItem item = this.getTopItemAt(x, y);
        if (item != null) {
            return (item.getZ() + Item.getCurrentHeight(item) - (item.getBaseItem().allowSit() ? 1 : 0));
        } else {
            return this.layout.getHeightAtSquare(x, y);
        }
    }

    public HabboItem getLowestChair(RoomTile tile) {
        HabboItem lowestChair = null;

        THashSet<HabboItem> items = this.getItemsAt(tile);
        if (items != null && !items.isEmpty()) {
            for (HabboItem item : items) {

                if (!item.getBaseItem().allowSit())
                    continue;

                if (lowestChair != null && lowestChair.getZ() < item.getZ())
                    continue;

                lowestChair = item;
            }
        }

        return lowestChair;
    }

    public HabboItem getTallestChair(RoomTile tile) {
        HabboItem lowestChair = null;

        THashSet<HabboItem> items = this.getItemsAt(tile);
        if (items != null && !items.isEmpty()) {
            for (HabboItem item : items) {

                if (!item.getBaseItem().allowSit())
                    continue;

                if (lowestChair != null && lowestChair.getZ() + Item.getCurrentHeight(lowestChair) > item.getZ() + Item.getCurrentHeight(item))
                    continue;

                lowestChair = item;
            }
        }

        return lowestChair;
    }

    public double getStackHeight(short x, short y, boolean calculateHeightmap, HabboItem exclude) {

        if (x < 0 || y < 0 || this.layout == null)
            return calculateHeightmap ? Short.MAX_VALUE : 0.0;

        if (Emulator.getPluginManager().isRegistered(FurnitureStackHeightEvent.class, true)) {
            FurnitureStackHeightEvent event = Emulator.getPluginManager().fireEvent(new FurnitureStackHeightEvent(x, y, this));
            if (event.hasPluginHelper()) {
                return calculateHeightmap ? event.getHeight() * 256.0D : event.getHeight();
            }
        }

        double height = this.layout.getHeightAtSquare(x, y);
        boolean canStack = true;

        THashSet<HabboItem> stackHelpers = this.getItemsAt(InteractionStackHelper.class, x, y);


        for (HabboItem item : stackHelpers) {
            if (item == exclude) continue;
            return calculateHeightmap ? item.getZ() * 256.0D : item.getZ();
        }


        HabboItem item = this.getTopItemAt(x, y, exclude);
        if (item != null) {
            canStack = item.getBaseItem().allowStack();
            height = item.getZ() + (item.getBaseItem().allowSit() ? 0 : Item.getCurrentHeight(item));
        }

        if (calculateHeightmap) {
            return (canStack ? height * 256.0D : Short.MAX_VALUE);
        }

        return canStack ? height : -1;
    }

    public double getStackHeight(short x, short y, boolean calculateHeightmap) {
        return this.getStackHeight(x, y, calculateHeightmap, null);
    }

    public boolean hasObjectTypeAt(Class<?> type, int x, int y) {
        THashSet<HabboItem> items = this.getItemsAt(x, y);

        for (HabboItem item : items) {
            if (item.getClass() == type) {
                return true;
            }
        }

        return false;
    }

    public boolean canSitOrLayAt(int x, int y) {
        if (this.hasHabbosAt(x, y))
            return false;

        THashSet<HabboItem> items = this.getItemsAt(x, y);

        return this.canSitAt(items) || this.canLayAt(items);
    }

    public boolean canSitAt(int x, int y) {
        if (this.hasHabbosAt(x, y))
            return false;

        return this.canSitAt(this.getItemsAt(x, y));
    }

    boolean canWalkAt(RoomTile roomTile) {
        if (roomTile == null) {
            return false;
        }

        if (roomTile.getState() == RoomTileState.INVALID)
            return false;

        HabboItem topItem = null;
        boolean canWalk = true;
        THashSet<HabboItem> items = this.getItemsAt(roomTile);
        if (items != null) {
            for (HabboItem item : items) {
                if (topItem == null) {
                    topItem = item;
                }

                if (item.getZ() > topItem.getZ()) {
                    topItem = item;
                    canWalk = topItem.isWalkable() || topItem.getBaseItem().allowWalk();
                } else if (item.getZ() == topItem.getZ() && canWalk) {
                    if ((!topItem.isWalkable() && !topItem.getBaseItem().allowWalk())
                            || (!item.getBaseItem().allowWalk() && !item.isWalkable())) {
                        canWalk = false;
                    }
                }
            }
        }

        return canWalk;
    }

    boolean canSitAt(THashSet<HabboItem> items) {
        if (items == null)
            return false;

        HabboItem tallestItem = null;

        for (HabboItem item : items) {
            if (tallestItem != null && tallestItem.getZ() + Item.getCurrentHeight(tallestItem) > item.getZ() + Item.getCurrentHeight(item))
                continue;

            tallestItem = item;
        }

        if (tallestItem == null)
            return false;

        return tallestItem.getBaseItem().allowSit();
    }

    public boolean canLayAt(int x, int y) {
        return this.canLayAt(this.getItemsAt(x, y));
    }

    boolean canLayAt(THashSet<HabboItem> items) {
        if (items == null || items.isEmpty())
            return true;

        HabboItem topItem = null;

        for (HabboItem item : items) {
            if ((topItem == null || item.getZ() > topItem.getZ())) {
                topItem = item;
            }
        }

        return (topItem == null || topItem.getBaseItem().allowLay());
    }

    public RoomTile getRandomWalkableTile() {
        for (int i = 0; i < 10; i++) {
            RoomTile tile = this.layout.getTile((short) (Math.random() * this.layout.getMapSizeX()), (short) (Math.random() * this.layout.getMapSizeY()));
            if (tile != null && tile.getState() != RoomTileState.BLOCKED && tile.getState() != RoomTileState.INVALID) {
                return tile;
            }
        }

        return null;
    }

    public Habbo getHabbo(String username) {
        for (Habbo habbo : this.getHabbos()) {
            if (habbo.getHabboInfo().getUsername().equalsIgnoreCase(username))
                return habbo;
        }
        return null;
    }

    public Habbo getHabbo(RoomUnit roomUnit) {
        for (Habbo habbo : this.getHabbos()) {
            if (habbo.getRoomUnit() == roomUnit)
                return habbo;
        }
        return null;
    }

    public Habbo getHabbo(int userId) {
        return this.currentHabbos.get(userId);
    }

    public Habbo getHabboByRoomUnitId(int roomUnitId) {
        for (Habbo habbo : this.getHabbos()) {
            if (habbo.getRoomUnit().getId() == roomUnitId)
                return habbo;
        }

        return null;
    }

    public void sendComposer(ServerMessage message) {
        for (Habbo habbo : this.getHabbos()) {
            if (habbo.getClient() == null) continue;

            habbo.getClient().sendResponse(message);
        }
    }

    public void sendComposerToHabbosWithRights(ServerMessage message) {
        for (Habbo habbo : this.getHabbos()) {
            if (this.hasRights(habbo)) {
                habbo.getClient().sendResponse(message);
            }
        }
    }

    public void petChat(ServerMessage message) {
        for (Habbo habbo : this.getHabbos()) {
            if (!habbo.getHabboStats().isIgnorePets())
                habbo.getClient().sendResponse(message);
        }
    }

    public void botChat(ServerMessage message) {
        if (message == null) {
            return;
        }

        for (Habbo habbo : this.getHabbos()) {
            if (habbo == null) { return ; }
            if (!habbo.getHabboStats().isIgnoreBots())
                habbo.getClient().sendResponse(message);
        }
    }

    private void loadRights(Connection connection) {
        this.rights.clear();
        try (PreparedStatement statement = connection.prepareStatement("SELECT user_id FROM room_rights WHERE room_id = ?")) {
            statement.setInt(1, this.id);
            try (ResultSet set = statement.executeQuery()) {
                while (set.next()) {
                    this.rights.add(set.getInt(DatabaseConstants.USER_ID));
                }
            }
        } catch (SQLException e) {
            log.error(CAUGHT_SQL_EXCEPTION, e);
        } catch (Exception e) {
            log.error(CAUGHT_EXCEPTION, e);
        }
    }

    private void loadBans(Connection connection) {
        this.bannedHabbos.clear();

        try (PreparedStatement statement = connection.prepareStatement("SELECT users.username, users.id, room_bans.* FROM room_bans INNER JOIN users ON room_bans.user_id = users.id WHERE ends > ? AND room_bans.room_id = ?")) {
            statement.setInt(1, Emulator.getIntUnixTimestamp());
            statement.setInt(2, this.id);
            try (ResultSet set = statement.executeQuery()) {
                while (set.next()) {
                    if (this.bannedHabbos.containsKey(set.getInt(DatabaseConstants.USER_ID)))
                        continue;

                    this.bannedHabbos.put(set.getInt(DatabaseConstants.USER_ID), new RoomBan(set));
                }
            }
        } catch (SQLException e) {
            log.error(CAUGHT_SQL_EXCEPTION, e);
        }
    }

    public RoomRightLevels getGuildRightLevel(Habbo habbo) {
        if (this.guildId > 0 && habbo.getHabboStats().hasGuild(this.guildId)) {
            Guild guild = Emulator.getGameEnvironment().getGuildManager().getGuild(this.guildId);

            if (Emulator.getGameEnvironment().getGuildManager().getOnlyAdmins(guild).get(habbo.getHabboInfo().getId()) != null)
                return RoomRightLevels.GUILD_ADMIN;

            if (guild.isRights()) {
                return RoomRightLevels.GUILD_RIGHTS;
            }
        }

        return RoomRightLevels.NONE;
    }

    /**
     * @deprecated Deprecated since 2.5.0. Use {@link #getGuildRightLevel(Habbo)} instead.
     */
    @Deprecated
    public int guildRightLevel(Habbo habbo) {
        return this.getGuildRightLevel(habbo).getLevel();
    }

    public boolean isOwner(Habbo habbo) {
        return habbo.getHabboInfo().getId() == this.ownerId || habbo.hasRight(Permission.ACC_ANYROOMOWNER);
    }

    public boolean hasRights(Habbo habbo) {
        return this.isOwner(habbo) || this.rights.contains(habbo.getHabboInfo().getId()) || (habbo.getRoomUnit().getRightsLevel() != RoomRightLevels.NONE && this.currentHabbos.containsKey(habbo.getHabboInfo().getId()));
    }

    public void giveRights(Habbo habbo) {
        if (habbo != null) {
            this.giveRights(habbo.getHabboInfo().getId());
        }
    }

    public void giveRights(int userId) {
        if (this.rights.contains(userId))
            return;

        if (this.rights.add(userId)) {
            try (Connection connection = Emulator.getDatabase().getDataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("INSERT INTO room_rights VALUES (?, ?)")) {
                statement.setInt(1, this.id);
                statement.setInt(2, userId);
                statement.execute();
            } catch (SQLException e) {
                log.error(CAUGHT_SQL_EXCEPTION, e);
            }
        }

        Habbo habbo = this.getHabbo(userId);

        if (habbo != null) {
            this.refreshRightsForHabbo(habbo);

            this.sendComposer(new FlatControllerAddedComposer(this, habbo.getHabboInfo().getId(), habbo.getHabboInfo().getUsername()).compose());
        } else {
            Habbo owner = Emulator.getGameEnvironment().getHabboManager().getHabbo(this.ownerId);

            if (owner != null) {
                MessengerBuddy buddy = owner.getMessenger().getFriend(userId);

                if (buddy != null) {
                    this.sendComposer(new FlatControllerAddedComposer(this, userId, buddy.getUsername()).compose());
                }
            }
        }
    }

    public void removeRights(int userId) {
        Habbo habbo = this.getHabbo(userId);

        if (Emulator.getPluginManager().fireEvent(new UserRightsTakenEvent(this.getHabbo(this.getOwnerId()), userId, habbo)).isCancelled())
            return;

        this.sendComposer(new FlatControllerRemovedComposer(this, userId).compose());

        if (this.rights.remove(userId)) {
            try (Connection connection = Emulator.getDatabase().getDataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("DELETE FROM room_rights WHERE room_id = ? AND user_id = ?")) {
                statement.setInt(1, this.id);
                statement.setInt(2, userId);
                statement.execute();
            } catch (SQLException e) {
                log.error(CAUGHT_SQL_EXCEPTION, e);
            }
        }

        if (habbo != null) {
            this.ejectUserFurni(habbo.getHabboInfo().getId());
            habbo.getRoomUnit().setRightsLevel(RoomRightLevels.NONE);
            habbo.getRoomUnit().removeStatus(RoomUnitStatus.FLAT_CONTROL);
            this.refreshRightsForHabbo(habbo);
        }
    }

    public void removeAllRights() {
        for (int userId : rights.toArray()) {
            this.ejectUserFurni(userId);
        }

        this.rights.clear();

        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("DELETE FROM room_rights WHERE room_id = ?")) {
            statement.setInt(1, this.id);
            statement.execute();
        } catch (SQLException e) {
            log.error(CAUGHT_SQL_EXCEPTION, e);
        }

        this.refreshRightsInRoom();
    }

    void refreshRightsInRoom() {
        Room room = this;
        for (Habbo habbo : this.getHabbos()) {
            if (habbo.getHabboInfo().getCurrentRoom() == room) {
                this.refreshRightsForHabbo(habbo);
            }
        }
    }

    public void refreshRightsForHabbo(Habbo habbo) {
        HabboItem item;
        RoomRightLevels flatCtrl = RoomRightLevels.NONE;
        if (habbo.getHabboStats().isRentingSpace()) {
            item = this.getHabboItem(habbo.getHabboStats().getRentedItemId());

            if (item != null) {
                return;
            }
        }

        if (habbo.hasRight(Permission.ACC_ANYROOMOWNER) || this.isOwner(habbo)) {
            habbo.getClient().sendResponse(new YouAreOwnerMessageComposer());
            flatCtrl = RoomRightLevels.MODERATOR;
        } else if (this.hasRights(habbo) && !this.hasGuild()) {
            flatCtrl = RoomRightLevels.RIGHTS;
        } else if (this.hasGuild()) {
            flatCtrl = this.getGuildRightLevel(habbo);
        }

        habbo.getClient().sendResponse(new YouAreControllerMessageComposer(flatCtrl));
        habbo.getRoomUnit().setStatus(RoomUnitStatus.FLAT_CONTROL, flatCtrl.getLevel() + "");
        habbo.getRoomUnit().setRightsLevel(flatCtrl);
        habbo.getRoomUnit().statusUpdate(true);

        if (flatCtrl.equals(RoomRightLevels.MODERATOR)) {
            habbo.getClient().sendResponse(new FlatControllersComposer(this));
        }
    }

    public THashMap<Integer, String> getUsersWithRights() {
        THashMap<Integer, String> rightsMap = new THashMap<>();

        if (!this.rights.isEmpty()) {
            try (Connection connection = Emulator.getDatabase().getDataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT users.username AS username, users.id as user_id FROM room_rights INNER JOIN users ON room_rights.user_id = users.id WHERE room_id = ?")) {
                statement.setInt(1, this.id);
                try (ResultSet set = statement.executeQuery()) {
                    while (set.next()) {
                        rightsMap.put(set.getInt(DatabaseConstants.USER_ID), set.getString("username"));
                    }
                }
            } catch (SQLException e) {
                log.error(CAUGHT_SQL_EXCEPTION, e);
            }
        }

        return rightsMap;
    }

    public void unbanHabbo(int userId) {
        RoomBan ban = this.bannedHabbos.remove(userId);

        if (ban != null) {
            ban.delete();
        }

        this.sendComposer(new UserUnbannedFromRoomComposer(this, userId).compose());
    }

    public boolean isBanned(Habbo habbo) {
        RoomBan ban = this.bannedHabbos.get(habbo.getHabboInfo().getId());

        boolean banned = ban != null && ban.getEndTimestamp() > Emulator.getIntUnixTimestamp() && !habbo.hasRight(Permission.ACC_ANYROOMOWNER) && !habbo.hasRight(Permission.ACC_ENTERANYROOM);

        if (!banned && ban != null) {
            this.unbanHabbo(habbo.getHabboInfo().getId());
        }

        return banned;
    }

    public TIntObjectHashMap<RoomBan> getBannedHabbos() {
        return this.bannedHabbos;
    }

    public void addRoomBan(RoomBan roomBan) {
        this.bannedHabbos.put(roomBan.getUserId(), roomBan);
    }

    public void makeSit(Habbo habbo) {
        if (habbo.getRoomUnit() == null) return;

        if (habbo.getRoomUnit().hasStatus(RoomUnitStatus.SIT) || !habbo.getRoomUnit().canForcePosture()) {
            return;
        }

        this.dance(habbo, DanceType.NONE);
        habbo.getRoomUnit().setCmdSit(true);
        habbo.getRoomUnit().setBodyRotation(RoomUserRotation.values()[habbo.getRoomUnit().getBodyRotation().getValue() - habbo.getRoomUnit().getBodyRotation().getValue() % 2]);
        habbo.getRoomUnit().setStatus(RoomUnitStatus.SIT, 0.5 + "");
        this.sendComposer(new UserUpdateComposer(habbo.getRoomUnit()).compose());
    }

    public void makeStand(Habbo habbo) {
        if (habbo.getRoomUnit() == null) return;

        HabboItem item = this.getTopItemAt(habbo.getRoomUnit().getX(), habbo.getRoomUnit().getY());
        if (item == null || !item.getBaseItem().allowSit() || !item.getBaseItem().allowLay()) {
            habbo.getRoomUnit().setCmdStand(true);
            habbo.getRoomUnit().setBodyRotation(RoomUserRotation.values()[habbo.getRoomUnit().getBodyRotation().getValue() - habbo.getRoomUnit().getBodyRotation().getValue() % 2]);
            habbo.getRoomUnit().removeStatus(RoomUnitStatus.SIT);
            this.sendComposer(new UserUpdateComposer(habbo.getRoomUnit()).compose());
        }
    }

    public void giveEffect(Habbo habbo, int effectId, int duration, boolean ignoreChecks) {
        if (habbo != null && habbo.getRoomUnit() != null && this.currentHabbos.containsKey(habbo.getHabboInfo().getId())) {
            this.giveEffect(habbo.getRoomUnit(), effectId, duration, ignoreChecks);
        }
    }

    public void giveEffect(Habbo habbo, int effectId, int duration) {
        if (habbo != null && habbo.getRoomUnit() != null && this.currentHabbos.containsKey(habbo.getHabboInfo().getId())) {
            this.giveEffect(habbo.getRoomUnit(), effectId, duration, false);
        }
    }

    public void giveEffect(RoomUnit roomUnit, int effectId, int duration) {
        this.giveEffect(roomUnit, effectId, duration, false);
    }

    public void giveEffect(RoomUnit roomUnit, int effectId, int duration, boolean ignoreChecks) {
        if (roomUnit == null || roomUnit.getRoom() == null) return;

        Habbo habbo = roomUnit.getRoom().getHabbo(roomUnit);

        if (roomUnit.getRoomUnitType() == RoomUnitType.USER && (habbo == null || habbo.getHabboInfo().isInGame() && !ignoreChecks)) { return; }
            if (duration == -1 || duration == Integer.MAX_VALUE) {
                duration = Integer.MAX_VALUE;
            } else {
                duration += Emulator.getIntUnixTimestamp();
            }

            if ((this.allowEffects || ignoreChecks) && !roomUnit.isSwimming()) {
                roomUnit.setEffectId(effectId, duration);
                this.sendComposer(new AvatarEffectMessageComposer(roomUnit).compose());
            }
    }

    public void giveHandItem(Habbo habbo, int handItem) {
        this.giveHandItem(habbo.getRoomUnit(), handItem);
    }

    public void giveHandItem(RoomUnit roomUnit, int handItem) {
        roomUnit.setHandItem(handItem);
        this.sendComposer(new CarryObjectMessageComposer(roomUnit).compose());
    }

    public void updateItem(HabboItem item) {
        if (!this.isLoaded()) {
            return;
        }

        if (item != null && item.getRoomId() == this.id && item.getBaseItem() != null) {
            if (item.getBaseItem().getType() == FurnitureType.FLOOR) {
                this.sendComposer(new ObjectUpdateMessageComposer(item).compose());
                this.updateTiles(this.getLayout().getTilesAt(this.layout.getTile(item.getX(), item.getY()), item.getBaseItem().getWidth(), item.getBaseItem().getLength(), item.getRotation()));
            } else if (item.getBaseItem().getType() == FurnitureType.WALL) {
                this.sendComposer(new ItemUpdateMessageComposer(item).compose());
            }
        }

    }

    public void updateItemState(HabboItem item) {
        if (!item.isLimited()) {
            this.sendComposer(new OneWayDoorStatusMessageComposer(item).compose());
        } else {
            this.sendComposer(new ObjectUpdateMessageComposer(item).compose());
        }

        if (item.getBaseItem().getType() == FurnitureType.FLOOR) {
            if (this.layout == null) return;

            this.updateTiles(this.getLayout().getTilesAt(this.layout.getTile(item.getX(), item.getY()), item.getBaseItem().getWidth(), item.getBaseItem().getLength(), item.getRotation()));

            if (item instanceof InteractionMultiHeight interactionMultiHeight) {
                interactionMultiHeight.updateUnitsOnItem(this);
            }
        }
    }

    public int getUserFurniCount(int userId) {
        return this.furniOwnerCount.get(userId);
    }

    public int getUserUniqueFurniCount(int userId) {
        THashSet<Item> items = new THashSet<>();

        for (HabboItem item : this.roomItems.valueCollection()) {
            if (!items.contains(item.getBaseItem()) && item.getUserId() == userId) items.add(item.getBaseItem());
        }

        return items.size();
    }

    public void ejectUserFurni(int userId) {
        THashSet<HabboItem> items = new THashSet<>();

        TIntObjectIterator<HabboItem> iterator = this.roomItems.iterator();

        for (int i = this.roomItems.size(); i-- > 0; ) {
            try {
                iterator.advance();
            } catch (Exception e) {
                break;
            }

            if (iterator.value().getUserId() == userId) {
                items.add(iterator.value());
                iterator.value().setRoomId(0);
            }
        }

        Habbo habbo = Emulator.getGameEnvironment().getHabboManager().getHabbo(userId);

        if (habbo != null) {
            habbo.getInventory().getItemsComponent().addItems(items);
            habbo.getClient().sendResponse(new UnseenItemsComposer(items));
        }

        for (HabboItem i : items) {
            this.pickUpItem(i, null);
        }
    }

    public void ejectUserItem(HabboItem item) {
        this.pickUpItem(item, null);
    }


    public void ejectAll() {
        this.ejectAll(null);
    }


    public void ejectAll(Habbo habbo) {
        THashMap<Integer, THashSet<HabboItem>> userItemsMap = new THashMap<>();

        synchronized (this.roomItems) {
            TIntObjectIterator<HabboItem> iterator = this.roomItems.iterator();

            for (int i = this.roomItems.size(); i-- > 0; ) {
                try {
                    iterator.advance();
                } catch (Exception e) {
                    break;
                }

                if (habbo != null && iterator.value().getUserId() == habbo.getHabboInfo().getId())
                    continue;

                if (iterator.value() instanceof InteractionPostIt)
                    continue;

                userItemsMap.computeIfAbsent(iterator.value().getUserId(), k -> new THashSet<>()).add(iterator.value());
            }
        }

        for (Map.Entry<Integer, THashSet<HabboItem>> entrySet : userItemsMap.entrySet()) {
            for (HabboItem i : entrySet.getValue()) {
                this.pickUpItem(i, null);
            }

            Habbo user = Emulator.getGameEnvironment().getHabboManager().getHabbo(entrySet.getKey());

            if (user != null) {
                user.getInventory().getItemsComponent().addItems(entrySet.getValue());
                user.getClient().sendResponse(new UnseenItemsComposer(entrySet.getValue()));
            }
        }
    }

    public void refreshGuild(Guild guild) {
        if (guild.getRoomId() == this.id) {
            THashSet<GuildMember> members = Emulator.getGameEnvironment().getGuildManager().getGuildMembers(guild.getId());

            for (Habbo habbo : this.getHabbos()) {
                Optional<GuildMember> member = members.stream().filter(m -> m.getUserId() == habbo.getHabboInfo().getId()).findAny();

                if (member.isEmpty()) continue;

                habbo.getClient().sendResponse(new HabboGroupDetailsMessageComposer(guild, habbo.getClient(), false, member.get()));
            }
        }

        this.refreshGuildRightsInRoom();
    }

    public void refreshGuildColors(Guild guild) {
        if (guild.getRoomId() == this.id) {
            TIntObjectIterator<HabboItem> iterator = this.roomItems.iterator();

            for (int i = this.roomItems.size(); i-- > 0; ) {
                try {
                    iterator.advance();
                } catch (Exception e) {
                    break;
                }

                HabboItem habboItem = iterator.value();

                if (habboItem instanceof InteractionGuildFurni interactionGuildFurni && interactionGuildFurni.getGuildId() == guild.getId()) {
                    this.updateItem(habboItem);
                }
            }
        }
    }

    public void refreshGuildRightsInRoom() {
        for (Habbo habbo : this.getHabbos()) {
            if ((habbo.getHabboInfo().getCurrentRoom() == this && habbo.getHabboInfo().getId() != this.ownerId)
                    && (!(habbo.hasRight(Permission.ACC_ANYROOMOWNER) || habbo.hasRight(Permission.ACC_MOVEROTATE))))
                this.refreshRightsForHabbo(habbo);
        }
    }


    public void idle(Habbo habbo) {
        habbo.getRoomUnit().setIdle();

        if (habbo.getRoomUnit().getDanceType() != DanceType.NONE) {
            this.dance(habbo, DanceType.NONE);
        }

        this.sendComposer(new SleepMessageComposer(habbo.getRoomUnit()).compose());
        WiredHandler.handle(WiredTriggerType.IDLES, habbo.getRoomUnit(), this, new Object[]{habbo});
    }

    public void unIdle(Habbo habbo) {
        if (habbo == null || habbo.getRoomUnit() == null) return;
        habbo.getRoomUnit().resetIdleTimer();
        this.sendComposer(new SleepMessageComposer(habbo.getRoomUnit()).compose());
        WiredHandler.handle(WiredTriggerType.UNIDLES, habbo.getRoomUnit(), this, new Object[]{habbo});
    }

    public void dance(Habbo habbo, DanceType danceType) {
        this.dance(habbo.getRoomUnit(), danceType);
    }

    public void dance(RoomUnit unit, DanceType danceType) {
        if (unit.getDanceType() != danceType) {
            boolean isDancing = !unit.getDanceType().equals(DanceType.NONE);
            unit.setDanceType(danceType);
            this.sendComposer(new DanceMessageComposer(unit).compose());

            if (danceType.equals(DanceType.NONE) && isDancing) {
                WiredHandler.handle(WiredTriggerType.STOPS_DANCING, unit, this, new Object[]{unit});
            } else if (!danceType.equals(DanceType.NONE) && !isDancing) {
                WiredHandler.handle(WiredTriggerType.STARTS_DANCING, unit, this, new Object[]{unit});
            }
        }
    }

    public void addToWordFilter(String word) {
        synchronized (this.wordFilterWords) {
            if (this.wordFilterWords.contains(word))
                return;


            try (Connection connection = Emulator.getDatabase().getDataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("INSERT IGNORE INTO room_wordfilter VALUES (?, ?)")) {
                statement.setInt(1, this.getId());
                statement.setString(2, word);
                statement.execute();
            } catch (SQLException e) {
                log.error(CAUGHT_SQL_EXCEPTION, e);
                return;
            }

            this.wordFilterWords.add(word);
        }
    }

    public void removeFromWordFilter(String word) {
        synchronized (this.wordFilterWords) {
            this.wordFilterWords.remove(word);

            try (Connection connection = Emulator.getDatabase().getDataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("DELETE FROM room_wordfilter WHERE room_id = ? AND word = ?")) {
                statement.setInt(1, this.getId());
                statement.setString(2, word);
                statement.execute();
            } catch (SQLException e) {
                log.error(CAUGHT_SQL_EXCEPTION, e);
            }
        }
    }

    public void handleWordQuiz(Habbo habbo, String answer) {
        synchronized (this.userVotes) {
            if (!this.wordQuiz.isEmpty() && !this.hasVotedInWordQuiz(habbo)) {
                answer = answer.replace(":", "");

                if (answer.equals("0")) {
                    this.noVotes++;
                } else if (answer.equals("1")) {
                    this.yesVotes++;
                }

                this.sendComposer(new QuestionAnsweredComposer(habbo.getHabboInfo().getId(), answer, this.noVotes, this.yesVotes).compose());
                this.userVotes.add(habbo.getHabboInfo().getId());
            }
        }
    }

    public void startWordQuiz(String question, int duration) {
        if (!this.hasActiveWordQuiz()) {
            this.wordQuiz = question;
            this.noVotes = 0;
            this.yesVotes = 0;
            this.userVotes.clear();
            this.wordQuizEnd = Emulator.getIntUnixTimestamp() + (duration / 1000);
            this.sendComposer(new QuestionComposer(duration, question).compose());
        }
    }

    public boolean hasActiveWordQuiz() {
        return Emulator.getIntUnixTimestamp() < this.wordQuizEnd;
    }

    public boolean hasVotedInWordQuiz(Habbo habbo) {
        return this.userVotes.contains(habbo.getHabboInfo().getId());
    }

    public void alert(String message) {
        this.sendComposer(new HabboBroadcastMessageComposer(message).compose());
    }

    public int itemCount() {
        return this.roomItems.size();
    }

    public void setJukeBoxActive(boolean jukeBoxActive) {
        this.jukeboxActive = jukeBoxActive;
        this.needsUpdate = true;
    }

    public boolean isHideWired() {
        return this.hideWired;
    }

    public void setHideWired(boolean hideWired) {
        this.hideWired = hideWired;

        if (this.hideWired) {
            for (HabboItem item : this.roomSpecialTypes.getTriggers()) {
                this.sendComposer(new RemoveFloorItemComposer(item).compose());
            }

            for (HabboItem item : this.roomSpecialTypes.getEffects()) {
                this.sendComposer(new RemoveFloorItemComposer(item).compose());
            }

            for (HabboItem item : this.roomSpecialTypes.getConditions()) {
                this.sendComposer(new RemoveFloorItemComposer(item).compose());
            }

            for (HabboItem item : this.roomSpecialTypes.getExtras()) {
                this.sendComposer(new RemoveFloorItemComposer(item).compose());
            }
        } else {
            this.sendComposer(new ObjectsMessageComposer(this.furniOwnerNames, this.roomSpecialTypes.getTriggers()).compose());
            this.sendComposer(new ObjectsMessageComposer(this.furniOwnerNames, this.roomSpecialTypes.getEffects()).compose());
            this.sendComposer(new ObjectsMessageComposer(this.furniOwnerNames, this.roomSpecialTypes.getConditions()).compose());
            this.sendComposer(new ObjectsMessageComposer(this.furniOwnerNames, this.roomSpecialTypes.getExtras()).compose());
        }
    }

    public FurnitureMovementError canPlaceFurnitureAt(HabboItem item, Habbo habbo, RoomTile tile, int rotation) {
        if (this.itemCount() >= Room.MAXIMUM_FURNI) {
            return FurnitureMovementError.MAX_ITEMS;
        }

        if (tile == null || tile.getState() == RoomTileState.INVALID) {
            return FurnitureMovementError.INVALID_MOVE;
        }

        rotation %= 8;
        if (this.hasRights(habbo) || this.getGuildRightLevel(habbo).isEqualOrGreaterThan(RoomRightLevels.GUILD_RIGHTS) || habbo.hasRight(Permission.ACC_MOVEROTATE)) {
            return FurnitureMovementError.NONE;
        }

        if (habbo.getHabboStats().isRentingSpace()) {
            HabboItem rentSpace = this.getHabboItem(habbo.getHabboStats().getRentedItemId());

            if (rentSpace != null) {
                if (!RoomLayout.squareInSquare(RoomLayout.getRectangle(rentSpace.getX(), rentSpace.getY(), rentSpace.getBaseItem().getWidth(), rentSpace.getBaseItem().getLength(), rentSpace.getRotation()), RoomLayout.getRectangle(tile.getX(), tile.getY(), item.getBaseItem().getWidth(), item.getBaseItem().getLength(), rotation))) {
                    return FurnitureMovementError.NO_RIGHTS;
                } else {
                    return FurnitureMovementError.NONE;
                }
            }
        }

        for (HabboItem area : this.getRoomSpecialTypes().getItemsOfType(InteractionBuildArea.class)) {
            if (((InteractionBuildArea) area).inSquare(tile) && ((InteractionBuildArea) area).isBuilder(habbo.getHabboInfo().getUsername())) {
                return FurnitureMovementError.NONE;
            }
        }

        return FurnitureMovementError.NO_RIGHTS;
    }

    public FurnitureMovementError furnitureFitsAt(RoomTile tile, HabboItem item, int rotation) {
        return furnitureFitsAt(tile, item, rotation, true);
    }

    public FurnitureMovementError furnitureFitsAt(RoomTile tile, HabboItem item, int rotation, boolean checkForUnits) {
        if (!this.layout.fitsOnMap(tile, item.getBaseItem().getWidth(), item.getBaseItem().getLength(), rotation))
            return FurnitureMovementError.INVALID_MOVE;

        if (item instanceof InteractionStackHelper) return FurnitureMovementError.NONE;


        THashSet<RoomTile> occupiedTiles = this.layout.getTilesAt(tile, item.getBaseItem().getWidth(), item.getBaseItem().getLength(), rotation);
        for (RoomTile t : occupiedTiles) {
            if (t.getState() == RoomTileState.INVALID) return FurnitureMovementError.INVALID_MOVE;
            if (!Emulator.getConfig().getBoolean("wired.place.under", false) || (Emulator.getConfig().getBoolean("wired.place.under", false) && !item.isWalkable() && !item.getBaseItem().allowSit() && !item.getBaseItem().allowLay())) {
                if (checkForUnits && this.hasHabbosAt(t.getX(), t.getY()))
                    return FurnitureMovementError.TILE_HAS_HABBOS;
                if (checkForUnits && this.hasBotsAt(t.getX(), t.getY())) return FurnitureMovementError.TILE_HAS_BOTS;
                if (checkForUnits && this.hasPetsAt(t.getX(), t.getY())) return FurnitureMovementError.TILE_HAS_PETS;
            }
        }

        Optional<HabboItem> stackHelper = this.getItemsAt(tile).stream().filter(InteractionStackHelper.class::isInstance).findAny();

        List<Pair<RoomTile, THashSet<HabboItem>>> tileFurniList = new ArrayList<>();
        for (RoomTile t : occupiedTiles) {
            tileFurniList.add(Pair.create(t, this.getItemsAt(t)));

            HabboItem topItem = this.getTopItemAt(t.getX(), t.getY(), item);
            if (topItem != null && !topItem.getBaseItem().allowStack() && !t.getAllowStack()) {
                return FurnitureMovementError.CANT_STACK;
            }

            if ((stackHelper.isPresent() && item.getBaseItem().getInteractionType().getType() == InteractionWater.class) || topItem != null && (topItem.getBaseItem().getInteractionType().getType() == InteractionWater.class && (item.getBaseItem().getInteractionType().getType() == InteractionWater.class || item.getBaseItem().getInteractionType().getType() != InteractionWaterItem.class))) {
                return FurnitureMovementError.CANT_STACK;
            }
        }

        if (!item.canStackAt(this, tileFurniList)) {
            return FurnitureMovementError.CANT_STACK;
        }

        return FurnitureMovementError.NONE;
    }

    public FurnitureMovementError placeFloorFurniAt(HabboItem item, RoomTile tile, int rotation, Habbo owner) {
        boolean pluginHelper = false;
        if (Emulator.getPluginManager().isRegistered(FurniturePlacedEvent.class, true)) {
            FurniturePlacedEvent event = Emulator.getPluginManager().fireEvent(new FurniturePlacedEvent(item, owner, tile));

            if (event.isCancelled()) {
                return FurnitureMovementError.CANCEL_PLUGIN_PLACE;
            }

            pluginHelper = event.hasPluginHelper();
        }

        THashSet<RoomTile> occupiedTiles = this.layout.getTilesAt(tile, item.getBaseItem().getWidth(), item.getBaseItem().getLength(), rotation);

        FurnitureMovementError fits = furnitureFitsAt(tile, item, rotation);

        if (!fits.equals(FurnitureMovementError.NONE) && !pluginHelper) {
            return fits;
        }

        double height = tile.getStackHeight();

        for (RoomTile tile2 : occupiedTiles) {
            double sHeight = tile2.getStackHeight();
            if (sHeight > height) {
                height = sHeight;
            }
        }

        if (Emulator.getPluginManager().isRegistered(FurnitureBuildheightEvent.class, true)) {
            FurnitureBuildheightEvent event = Emulator.getPluginManager().fireEvent(new FurnitureBuildheightEvent(item, owner, 0.00, height));
            if (event.hasChangedHeight()) {
                height = event.getUpdatedHeight();
            }
        }

        item.setZ(height);
        item.setX(tile.getX());
        item.setY(tile.getY());
        item.setRotation(rotation);
        if (!this.furniOwnerNames.containsKey(item.getUserId()) && owner != null) {
            this.furniOwnerNames.put(item.getUserId(), owner.getHabboInfo().getUsername());
        }

        item.needsUpdate(true);
        this.addHabboItem(item);
        item.setRoomId(this.id);
        item.onPlace(this);
        this.updateTiles(occupiedTiles);
        this.sendComposer(new ObjectAddMessageComposer(item, this.getFurniOwnerName(item.getUserId())).compose());

        for (RoomTile t : occupiedTiles) {
            this.updateHabbosAt(t.getX(), t.getY());
            this.updateBotsAt(t.getX(), t.getY());
        }

        Emulator.getThreading().run(item);
        return FurnitureMovementError.NONE;
    }

    public FurnitureMovementError placeWallFurniAt(HabboItem item, String wallPosition, Habbo owner) {
        if (!(this.hasRights(owner) || this.getGuildRightLevel(owner).isEqualOrGreaterThan(RoomRightLevels.GUILD_RIGHTS))) {
            return FurnitureMovementError.NO_RIGHTS;
        }

        if (Emulator.getPluginManager().isRegistered(FurniturePlacedEvent.class, true)) {
            Event furniturePlacedEvent = new FurniturePlacedEvent(item, owner, null);
            Emulator.getPluginManager().fireEvent(furniturePlacedEvent);

            if (furniturePlacedEvent.isCancelled())
                return FurnitureMovementError.CANCEL_PLUGIN_PLACE;
        }

        item.setWallPosition(wallPosition);
        if (!this.furniOwnerNames.containsKey(item.getUserId()) && owner != null) {
            this.furniOwnerNames.put(item.getUserId(), owner.getHabboInfo().getUsername());
        }
        this.sendComposer(new ItemAddMessageComposer(item, this.getFurniOwnerName(item.getUserId())).compose());
        item.needsUpdate(true);
        this.addHabboItem(item);
        item.setRoomId(this.id);
        item.onPlace(this);
        Emulator.getThreading().run(item);
        return FurnitureMovementError.NONE;
    }

    public FurnitureMovementError moveFurniTo(HabboItem item, RoomTile tile, int rotation, Habbo actor) {
        return moveFurniTo(item, tile, rotation, actor, true, true);
    }

    public FurnitureMovementError moveFurniTo(HabboItem item, RoomTile tile, int rotation, Habbo actor, boolean sendUpdates) {
        return moveFurniTo(item, tile, rotation, actor, sendUpdates, true);
    }

    public FurnitureMovementError moveFurniTo(HabboItem item, RoomTile tile, int rotation, Habbo actor, boolean sendUpdates, boolean checkForUnits) {
        RoomTile oldLocation = this.layout.getTile(item.getX(), item.getY());

        boolean pluginHelper = false;
        if (Emulator.getPluginManager().isRegistered(FurnitureMovedEvent.class, true)) {
            FurnitureMovedEvent event = Emulator.getPluginManager().fireEvent(new FurnitureMovedEvent(item, actor, oldLocation, tile));
            if (event.isCancelled()) {
                return FurnitureMovementError.CANCEL_PLUGIN_MOVE;
            }
            pluginHelper = event.hasPluginHelper();
        }

        boolean magicTile = item instanceof InteractionStackHelper;

        Optional<HabboItem> stackHelper = this.getItemsAt(tile).stream().filter(InteractionStackHelper.class::isInstance).findAny();

        //Check if can be placed at new position
        THashSet<RoomTile> occupiedTiles = this.layout.getTilesAt(tile, item.getBaseItem().getWidth(), item.getBaseItem().getLength(), rotation);
        THashSet<RoomTile> newOccupiedTiles = this.layout.getTilesAt(tile, item.getBaseItem().getWidth(), item.getBaseItem().getLength(), rotation);

        HabboItem topItem = this.getTopItemAt(occupiedTiles, null);

        if ((stackHelper.isEmpty() && !pluginHelper) || item.getBaseItem().getInteractionType().getType() == InteractionWater.class) {
            if (oldLocation != tile) {
                for (RoomTile t : occupiedTiles) {
                    HabboItem tileTopItem = this.getTopItemAt(t.getX(), t.getY());
                    if (!magicTile && (tileTopItem != null && tileTopItem != item ? (t.getState().equals(RoomTileState.INVALID) || !t.getAllowStack() || !tileTopItem.getBaseItem().allowStack() ||
                            (tileTopItem.getBaseItem().getInteractionType().getType() == InteractionWater.class && (item.getBaseItem().getInteractionType().getType() != InteractionWaterItem.class || item.getBaseItem().getInteractionType().getType() == InteractionWater.class))) : this.calculateTileState(t, item).equals(RoomTileState.INVALID)) || stackHelper.isPresent() && item.getBaseItem().getInteractionType().getType() == InteractionWater.class) {
                        return FurnitureMovementError.CANT_STACK;
                    }

                    if (!Emulator.getConfig().getBoolean("wired.place.under", false) || (Emulator.getConfig().getBoolean("wired.place.under", false) && !item.isWalkable() && !item.getBaseItem().allowSit())) {
                        if (checkForUnits && !magicTile) {
                            if (this.hasHabbosAt(t.getX(), t.getY()))
                                return FurnitureMovementError.TILE_HAS_HABBOS;
                            if (this.hasBotsAt(t.getX(), t.getY()))
                                return FurnitureMovementError.TILE_HAS_BOTS;
                            if (this.hasPetsAt(t.getX(), t.getY()))
                                return FurnitureMovementError.TILE_HAS_PETS;
                        }
                    }
                }
            }

            List<Pair<RoomTile, THashSet<HabboItem>>> tileFurniList = new ArrayList<>();
            for (RoomTile t : occupiedTiles) {
                tileFurniList.add(Pair.create(t, this.getItemsAt(t)));
            }

            if (!magicTile && !item.canStackAt(this, tileFurniList)) {
                return FurnitureMovementError.CANT_STACK;
            }
        }

        THashSet<RoomTile> oldOccupiedTiles = this.layout.getTilesAt(this.layout.getTile(item.getX(), item.getY()), item.getBaseItem().getWidth(), item.getBaseItem().getLength(), item.getRotation());

        int oldRotation = item.getRotation();

        if (oldRotation != rotation) {
            item.setRotation(rotation);
            if (Emulator.getPluginManager().isRegistered(FurnitureRotatedEvent.class, true)) {
                Event furnitureRotatedEvent = new FurnitureRotatedEvent(item, actor, oldRotation);
                Emulator.getPluginManager().fireEvent(furnitureRotatedEvent);

                if (furnitureRotatedEvent.isCancelled()) {
                    item.setRotation(oldRotation);
                    return FurnitureMovementError.CANCEL_PLUGIN_ROTATE;
                }
            }

            if ((stackHelper.isEmpty() && topItem != null && topItem != item && !topItem.getBaseItem().allowStack()) || (topItem != null && topItem != item && topItem.getZ() + Item.getCurrentHeight(topItem) + Item.getCurrentHeight(item) > MAXIMUM_FURNI_HEIGHT)) {
                item.setRotation(oldRotation);
                return FurnitureMovementError.CANT_STACK;
            }

            // )
        }
        //Place at new position

        double height;

        if (stackHelper.isPresent()) {
            height = stackHelper.get().getExtradata().isEmpty() ? Double.parseDouble("0.0") : (Double.parseDouble(stackHelper.get().getExtradata()) / 100);
        } else if (item == topItem) {
            height = item.getZ();
        } else {
            height = this.getStackHeight(tile.getX(), tile.getY(), false, item);
            for (RoomTile til : occupiedTiles) {
                double sHeight = this.getStackHeight(til.getX(), til.getY(), false, item);
                if (sHeight > height) {
                    height = sHeight;
                }
            }
        }

        if (height > MAXIMUM_FURNI_HEIGHT) return FurnitureMovementError.CANT_STACK;
        if (height < this.getLayout().getHeightAtSquare(tile.getX(), tile.getY()))
            return FurnitureMovementError.CANT_STACK; //prevent furni going under the floor

        if (Emulator.getPluginManager().isRegistered(FurnitureBuildheightEvent.class, true)) {
            FurnitureBuildheightEvent event = Emulator.getPluginManager().fireEvent(new FurnitureBuildheightEvent(item, actor, 0.00, height));
            if (event.hasChangedHeight()) {
                height = event.getUpdatedHeight();
            }
        }

        if (height > MAXIMUM_FURNI_HEIGHT) return FurnitureMovementError.CANT_STACK;
        if (height < this.getLayout().getHeightAtSquare(tile.getX(), tile.getY()))
            return FurnitureMovementError.CANT_STACK; //prevent furni going under the floor

        item.setX(tile.getX());
        item.setY(tile.getY());
        item.setZ(height);
        if (magicTile) {
            item.setZ(tile.getZ());
            item.setExtradata("" + item.getZ() * 100);
        }
        if (item.getZ() > MAXIMUM_FURNI_HEIGHT) {
            item.setZ(MAXIMUM_FURNI_HEIGHT);
        }


        //Update Furniture
        item.onMove(this, oldLocation, tile);
        item.needsUpdate(true);
        Emulator.getThreading().run(item);

        if (sendUpdates) {
            this.sendComposer(new ObjectUpdateMessageComposer(item).compose());
        }

        //Update old & new tiles
        occupiedTiles.removeAll(oldOccupiedTiles);
        occupiedTiles.addAll(oldOccupiedTiles);
        this.updateTiles(occupiedTiles);

        //Update Habbos at old position
        for (RoomTile t : occupiedTiles) {
            this.updateHabbosAt(
                    t.getX(),
                    t.getY(),
                    this.getHabbosAt(t.getX(), t.getY())
                            /*.stream()
                            .filter(h -> !h.getRoomUnit().hasStatus(RoomUnitStatus.MOVE) || h.getRoomUnit().getGoal() == t)
                            .collect(Collectors.toCollection(THashSet::new))*/
            );
            this.updateBotsAt(t.getX(), t.getY());
        }
        if (Emulator.getConfig().getBoolean("wired.place.under", false)) {
            for (RoomTile t : newOccupiedTiles) {
                for (Habbo h : this.getHabbosAt(t.getX(), t.getY())) {
                    try {
                        item.onWalkOn(h.getRoomUnit(), this, null);
                    } catch (Exception ignored) {

                    }
                }
            }
        }
        return FurnitureMovementError.NONE;
    }

    public FurnitureMovementError slideFurniTo(HabboItem item, RoomTile tile, int rotation) {
        boolean magicTile = item instanceof InteractionStackHelper;

        //Check if can be placed at new position
        THashSet<RoomTile> occupiedTiles = this.layout.getTilesAt(tile, item.getBaseItem().getWidth(), item.getBaseItem().getLength(), rotation);

        List<Pair<RoomTile, THashSet<HabboItem>>> tileFurniList = new ArrayList<>();
        for (RoomTile t : occupiedTiles) {
            tileFurniList.add(Pair.create(t, this.getItemsAt(t)));
        }

        if (!magicTile && !item.canStackAt(this, tileFurniList)) {
            return FurnitureMovementError.CANT_STACK;
        }

        item.setRotation(rotation);

        //Place at new position
        if (magicTile) {
            item.setZ(tile.getZ());
            item.setExtradata("" + item.getZ() * 100);
        }
        if (item.getZ() > MAXIMUM_FURNI_HEIGHT) {
            item.setZ(MAXIMUM_FURNI_HEIGHT);
        }
        double offset = this.getStackHeight(tile.getX(), tile.getY(), false, item) - item.getZ();
        this.sendComposer(new FloorItemOnRollerComposer(item, null, tile, offset, this).compose());

        //Update Habbos at old position
        for (RoomTile t : occupiedTiles) {
            this.updateHabbosAt(t.getX(), t.getY());
            this.updateBotsAt(t.getX(), t.getY());
        }
        return FurnitureMovementError.NONE;
    }

    public THashSet<RoomUnit> getRoomUnits() {
        return getRoomUnits(null);
    }

    public THashSet<RoomUnit> getRoomUnits(RoomTile atTile) {
        THashSet<RoomUnit> units = new THashSet<>();

        for (Habbo habbo : this.currentHabbos.values()) {
            if (habbo != null && habbo.getRoomUnit() != null && habbo.getRoomUnit().getRoom() != null && habbo.getRoomUnit().getRoom().getId() == this.getId() && (atTile == null || habbo.getRoomUnit().getCurrentLocation() == atTile)) {
                units.add(habbo.getRoomUnit());
            }
        }

        for (Pet pet : this.currentPets.valueCollection()) {
            if (pet != null && pet.getRoomUnit() != null && pet.getRoomUnit().getRoom() != null && pet.getRoomUnit().getRoom().getId() == this.getId() && (atTile == null || pet.getRoomUnit().getCurrentLocation() == atTile)) {
                units.add(pet.getRoomUnit());
            }
        }

        for (Bot bot : this.currentBots.valueCollection()) {
            if (bot != null && bot.getRoomUnit() != null && bot.getRoomUnit().getRoom() != null && bot.getRoomUnit().getRoom().getId() == this.getId() && (atTile == null || bot.getRoomUnit().getCurrentLocation() == atTile)) {
                units.add(bot.getRoomUnit());
            }
        }

        return units;
    }

    public Collection<RoomUnit> getRoomUnitsAt(RoomTile tile) {
        THashSet<RoomUnit> roomUnits = getRoomUnits();
        return roomUnits.stream().filter(unit -> unit.getCurrentLocation() == tile).collect(Collectors.toSet());
    }
}
