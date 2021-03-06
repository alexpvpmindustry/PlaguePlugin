package plague;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import arc.*;
import arc.graphics.Color;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.entities.Effects;
import mindustry.entities.type.*;
import mindustry.game.EventType;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.maps.MapException;
import mindustry.net.Administration;
import mindustry.plugin.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.ChargeTurret;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.units.UnitFactory;

import static arc.util.Log.info;
import static java.lang.Math.abs;
import java.util.prefs.Preferences;

import static java.lang.Math.random;
import static mindustry.Vars.*;
import static mindustry.Vars.player;

public class PlagueMod extends Plugin{

    public int teams = 0;
    public int infected = 0;
    public int survivors = 0;

    private Preferences prefs;

	// TPS = 40
    //in seconds
    public static final float spawnDelay = 60 * 4;
    //health requirement needed to capture a hex; no longer used
    public static final float healthRequirement = 3500;
    //item requirement to captured a hex
    public static final int itemRequirement = 1500;

    public static final int messageTime = 1;

    // Rank stuff
    private static final int regularTime = 1440; // 24 hours in minutes

    //in ticks: 60 minutes: 60 * 60 * 60
    private int roundTime = 60 * 60 * 40;
    //in ticks: 30 seconds
    private int escapeTicksLeft = 60 * 60 * 10;
    private final static int infectTime = 60 * 60 * 2;
    private final static int plagueInfluxTime = 60 * 60 * 1, announcementTime = 60 * 60 * 5, survivorWarnTime = 60 * 60 * 10, draugIncomeTime = 60 * 20
            , minuteTime = 60 * 60, creepFXTime = 60 * 10, creepSpreadTime = 60 * 10, surgeCheckTime = 60 * 1;

    private final static int timerPlagueInflux = 0, timerAnnouncement = 1, timerSurvivorWarn = 2, timerDraugIncome = 3, timerMinute = 4
            , timerCreepFX = 5, timerCreepSpread = 6, timerCheckSurge = 7;

    private int lastMin;

    private final Rules rules = new Rules();
    private Rules survivorBanned = new Rules();
    private Rules plagueBanned = new Rules();
    private Interval interval = new Interval(10);

    private boolean restarting = false, registered = false;

    private Array<Array<ItemStack>> loadouts = new Array<>(4);

    private double counter = 0f;
    private String mapName;
    private String mapAuthor;

    private int[] plagueCore = new int[2];


  	private String[] announcements = {"Join the discord at: [purple]https://discord.gg/GEnYcSv", "Use [accent]/hub[white] to return to the hub!","INSERT MAP VOTE MESSAGE"};
  	private int announcementIndex = 0;
    
    private Map<String, Team> lastTeam = new HashMap<String, Team>();
    private List<String> needsChanging = new ArrayList<>();

    private HashMap<Team, List<Tile>> draugCount = new HashMap<Team, List<Tile>>();
    private List<Tile> eradTiles = new ArrayList<>();
    private static final int voteSize = 5;
    private List<String> mapList = new ArrayList<>();
    private List<Integer> votableMaps = new ArrayList<>();
    private List<Integer> mapVotes = new ArrayList<>();
    private HashMap<String, Integer> playerMapVote = new HashMap<>();

    private HashMap<String, CustomPlayer> playerUtilMap = new HashMap<>();

    private DBInterface playerDB = new DBInterface("players");

    private boolean creep[][];
    private List<Tile> creepPerimeter = new ArrayList<>();
    List<String> creepStoppers = new ArrayList<>();
    
    private int[] survivorSurgeUnlocks = {500, 1000, 2500, 5000};
    private int[] plagueSurgeUnlocks = new int[200]; // New limit is 200 erads.


    private HashMap<Team, Integer> teamSurgePoints = new HashMap<>();
    private int plagueEradCap = 0;
    private int plagueErads = 0;

    private boolean escaping = false;

    @Override
    public void init(){
        playerDB.connect("data/server_data.db");
        creepStoppers.addAll(Arrays.asList(Blocks.thoriumWall.name, Blocks.thoriumWallLarge.name, Blocks.plastaniumWall.name, Blocks.plastaniumWallLarge.name,
                Blocks.phaseWall.name, Blocks.phaseWallLarge.name, Blocks.surgeWall.name, Blocks.surgeWallLarge.name, Blocks.titaniumWall.name,
                Blocks.titaniumWallLarge.name));
        plagueSurgeUnlocks[0] = 1000;
        plagueSurgeUnlocks[1] = 1000;
        plagueSurgeUnlocks[2] = 1000;
        plagueSurgeUnlocks[3] = 1000;
        for(int i = 4; i < 200; i++){
            plagueSurgeUnlocks[i] = 2500;
        }

    	loadouts.add(ItemStack.list(Items.copper, 25000, Items.lead, 25000, Items.graphite, 8000, Items.silicon, 8000, Items.titanium, 10000, Items.metaglass, 500, Items.surgealloy, 15));
    	loadouts.add(ItemStack.list(Items.titanium, 1000, Items.graphite, 400, Items.silicon, 400));
        rules.pvp = !true;
        rules.tags.put("plague", "true");
        rules.loadout = loadouts.get(0);
        rules.buildCostMultiplier = 1f;
        rules.buildSpeedMultiplier = 2;
        rules.blockHealthMultiplier = 1f;
        rules.unitBuildSpeedMultiplier = 1f;
        rules.playerDamageMultiplier = 0f;
        //rules.enemyCoreBuildRadius = 100 * tilesize;
        rules.unitDamageMultiplier = 1f;
        rules.playerHealthMultiplier = 0.5f;
        rules.canGameOver = false;
        rules.reactorExplosions = false;
        rules.respawnTime = 0;
        // rules.bannedBlocks.addAll(Blocks.solarPanel, Blocks.largeSolarPanel);
        rules.bannedBlocks.addAll(Blocks.arc, Blocks.melter, Blocks.surgeWall, Blocks.surgeWallLarge, Blocks.phaseWall, Blocks.phaseWallLarge);
        rules.bannedBlocks.addAll(Blocks.wraithFactory, Blocks.ghoulFactory, Blocks.glaivePad);

        init_rules();

        // Disable lancer pierce:
        Block lancer = Vars.content.blocks().find(block -> block.name.equals("lancer"));
        ((ChargeTurret)(lancer)).shootType = PlagueData.getLLaser();



        netServer.assigner = (player, players) -> {
            playerDB.loadRow(player.uuid);
            // Make admins
            int playTime = (int) playerDB.entries.get(player.uuid).get("playTime");
            int rankLevel = (int) playerDB.entries.get(player.uuid).get("rankLevel");
            int donateLevel = (int) playerDB.entries.get(player.uuid).get("donateLevel");
            playerUtilMap.put(player.uuid,new CustomPlayer(player, rankLevel, donateLevel, playTime));

            if(playerUtilMap.get(player.uuid).rank == 4) player.isAdmin = true;



            if(lastTeam.containsKey(player.uuid)){
                Team prev = lastTeam.get(player.uuid);
                if(teamSize(prev) > 0 && prev != Team.blue && prev != Team.crux){
                    survivors++;
                    if(player.isAdmin){
                        player.name = filterColor(player.name, "[green]");
                    }else{
                        player.name = filterColor(player.name, "[olive]");
                    }
                    return prev;
                }
            }

            if(counter < infectTime){
                if(player.isAdmin){
                    player.name = filterColor(player.name, "[blue]");
                }else{
                    player.name = filterColor(player.name, "[royal]");
                }

                return Team.blue;
            }else{
                infected ++;
                if(playerUtilMap.get(player.uuid).rank != 0 || playerUtilMap.get(player.uuid).donateLevel != 0){
                    allowCC(player, true);
                }else{
                    Call.onSetRules(player.con, plagueBanned);
                }
                playerUtilMap.get(player.uuid).infected = true;
                if(player.isAdmin){
                    player.name = filterColor(player.name, "[red]");
                }else{
                    player.name = filterColor(player.name, "[scarlet]");
                }
                return Team.crux;
            }
        };

        netServer.admins.addChatFilter((player, text) -> {
            for(String swear : CurseFilter.swears){
                text = text.replaceAll("(?i)" + swear, "");
            }
            return text;
        });

        netServer.admins.addActionFilter((action) -> {
            // If server is not synced with player, server will throw "Failed to to read remote method 'onTileTapped'!" error

            if(action.player != null){
                if(creep[action.tile.x][action.tile.y] && action.player.getTeam() != Team.crux && action.type == Administration.ActionType.placeBlock) {
                    return false;
                }


                if(action.player.getTeam() == Team.crux && !creep[action.tile.x][action.tile.y] && action.type == Administration.ActionType.placeBlock){
                    return false;
                }

                if(action.player.getTeam() == Team.crux && plagueBanned.bannedBlocks.contains(action.block)
                        && !(playerUtilMap.get(action.player.uuid).rank != 0 && action.block != null && action.block == Blocks.commandCenter)
                        && !(playerUtilMap.get(action.player.uuid).donateLevel != 0 && action.block != null && action.block == Blocks.commandCenter)){
                    return false;
                }

                if(action.player.getTeam() != Team.crux && action.player.getTeam() != Team.blue && survivorBanned.bannedBlocks.contains(action.block)){
                    return false;
                }

                if(action.type == Administration.ActionType.withdrawItem){
                    if(action.item.flammability != 0 || action.item.explosiveness != 0 || action.item == Items.surgealloy){
                        return false;
                    }
                }

                CoreBlock.CoreEntity nearestCore = state.teams.closestCore(action.tile.x, action.tile.y, action.player.getTeam());
                CoreBlock.CoreEntity nearestECore = state.teams.closestEnemyCore(action.tile.x, action.tile.y, action.player.getTeam());
                if(action.block != null && action.block == Blocks.unloader &&
                        cartesianDistance(action.tile.x, action.tile.y, nearestCore.tileX(), nearestCore.tileY()) < 10 ||
                        cartesianDistance(action.tile.x, action.tile.y, nearestECore.tileX(), nearestECore.tileY()) < 10){
                    action.player.sendMessage("[scarlet]Unloaders [accent]can not be placed near cores.");
                    return false;
                }

                if(action.type == Administration.ActionType.configure && action.block == Blocks.commandCenter
                        && playerUtilMap.get(action.player.uuid).rank == 0 && playerUtilMap.get(action.player.uuid).donateLevel == 0){
                    return false;
                }
            }

            if(action.type == Administration.ActionType.placeBlock && action.block == Blocks.revenantFactory && plagueErads >= plagueEradCap){
                return false;
            }



            if((action.type == Administration.ActionType.breakBlock || action.type == Administration.ActionType.placeBlock) && (action.tile.block() == Blocks.powerSource || action.tile.block() == Blocks.itemSource)){
                return false;
            }
            if(action.type == Administration.ActionType.configure && action.tile.block() == Blocks.powerSource && action.player != null){
                // Call.sendMessage(action.player.name + " is mucking with the power infinite");
                action.player.sendMessage("[accent]You just desynced yourself. Use [scarlet]/sync[accent] to resync");
                return false;
            }
            return true;
        });


        AtomicBoolean infectCountOn = new AtomicBoolean(true);
        int[] lastMod = {100};
        List<Team> draugChecked = new ArrayList<>();
        Events.on(EventType.Trigger.update, ()-> {

            if (counter+1 < infectTime && ((int) Math.ceil(counter / 60)-1) % 20 == 0){
                if(infectCountOn.get()){
                    Call.sendMessage("[accent]You have [scarlet]" + (int) Math.ceil((infectTime - counter) / 60) + " [accent]seconds left to place a core. Place any block to place a core.");
                    infectCountOn.set(false);
                }
            }else{
                infectCountOn.set(true);
            }

            if(escaping) escapeTicksLeft -= Time.delta();


            if (!restarting && escaping && escapeTicksLeft % (60*60) > lastMod[0] && escapeTicksLeft > 1){
                    int min = escapeTicksLeft / 60 / 60 + 1;
                    Call.sendMessage("[scarlet]" + min + "[accent]" + (min > 1 ? " minutes" : " minute") + " until the [green]Survivors [accent]escape!");
                }
            lastMod[0] = escapeTicksLeft % (60*60);

            boolean alive = false;

            boolean draugTime = interval.get(timerDraugIncome, draugIncomeTime);
            boolean surgeTime = interval.get(timerCheckSurge, surgeCheckTime);

            for (Player player : playerGroup.all()) {
                Team ply_team = player.getTeam();
                if (ply_team != Team.derelict && ply_team != Team.crux && ply_team.cores().isEmpty() && counter > infectTime) {
                    infect(player);
                }
                if (needsChanging.contains(player.uuid) && !player.dead) {
                    player.setTeam(Team.blue);
                    needsChanging.remove(player.uuid);
                }
                if(ply_team != Team.derelict && ply_team != Team.crux){
                    alive = true;
                }

                if(surgeTime && state.teams.cores(ply_team).size > 0){
                    int amount = state.teams.cores(ply_team).get(0).items.get(Items.surgealloy);
                    boolean isPlague = ply_team == Team.crux;
                    if(!teamSurgePoints.containsKey(ply_team)) teamSurgePoints.put(ply_team, 0);
                    int p = teamSurgePoints.get(ply_team);
                    if(isPlague){
                        if(p < plagueSurgeUnlocks.length && amount >= plagueSurgeUnlocks[p]){
                            state.teams.cores(ply_team).get(0).items.remove(Items.surgealloy, plagueSurgeUnlocks[p]);
                            amount -= plagueSurgeUnlocks[p];
                            p ++;
                            teamSurgePoints.put(ply_team, p);
                            plagueUnlock(p);
                        }
                    }else{
                        if(p < survivorSurgeUnlocks.length && amount >= survivorSurgeUnlocks[p]){
                            state.teams.cores(ply_team).get(0).items.remove(Items.surgealloy, survivorSurgeUnlocks[p]);
                            amount -= survivorSurgeUnlocks[p];
                            p ++;
                            teamSurgePoints.put(ply_team, p);
                            survivorUnlock(p);
                        }
                    }
                    if((isPlague && p < plagueSurgeUnlocks.length) || (!isPlague && p < survivorSurgeUnlocks.length)){
                        Call.onInfoPopup(player.con,amount + "\uF82C / " + (isPlague ? plagueSurgeUnlocks[p] : survivorSurgeUnlocks[p]) + "\uF82C", 1f, 20, 50, 20, 450, 0);
                    }else{
                        Call.onInfoPopup(player.con,amount + "\uF82C / MAX\uF82C", 1f, 20, 50, 20, 450, 0);
                    }
                }

                if(draugTime && draugCount.containsKey(ply_team) && !draugChecked.contains(ply_team) && state.teams.cores(ply_team).size > 0){
                    draugChecked.add(ply_team);
                    CoreBlock.CoreEntity teamCore = state.teams.cores(ply_team).get(0);
                    for(int i = 0; i < draugCount.get(ply_team).size() || i < 40; i++){ // Limited draugs to 40 per team
                        Call.transferItemTo(Items.copper, 40, teamCore.x, teamCore.y, teamCore.tile);
                        Call.transferItemTo(Items.lead, 40, teamCore.x, teamCore.y, teamCore.tile);
                    }
                }

            }
            draugChecked.clear();
            if((!alive && counter > infectTime) || escapeTicksLeft < 0){
                endGame(alive);
            }
            if(counter > infectTime && counter < infectTime*2 && infected == 0 && playerGroup.all().size > 0){
                Player player = playerGroup.all().random();
                if(playerUtilMap.containsKey(player.uuid)) infect(player);
            }

            if (interval.get(timerPlagueInflux, plagueInfluxTime)){
                Tile tile = Team.crux.cores().get(0).tile;
                for(ItemStack stack : loadouts.get(1)){
                    Call.transferItemTo(stack.item, stack.amount, tile.drawx(), tile.drawy(), tile);
                }
                // Call.sendMessage("The [red]Plague [white] was reinforced with resources");
            }

            if (interval.get(timerAnnouncement, announcementTime)){
                Call.sendMessage(announcements[announcementIndex]);
                announcementIndex = (announcementIndex + 1) % announcements.length;
            }

            if (interval.get(timerCreepSpread, creepSpreadTime)){
                expandCreep();
                int n = 0;
                for(Tile t : creepPerimeter){
                    n ++;
                    if(n % 5 != 0) continue;
                    Call.onEffectReliable(Fx.heal, t.x*tilesize, t.y*tilesize, 0, Color.green);
                }
            }

            if (interval.get(timerMinute, minuteTime)) {
                for(Player player : playerGroup.all()){
                    playerUtilMap.get(player.uuid).playTime += 1;
                    Call.setHudTextReliable(player.con, "[accent]Play time: [scarlet]" + playerUtilMap.get(player.uuid).playTime + "[accent] mins.");
                    if(playerUtilMap.get(player.uuid).rank == 0 && playerUtilMap.get(player.uuid).playTime >= regularTime){
                        Call.sendMessage(filterColor(player.name, "[gold]") + "[royal] has ranked up to regular!");
                        playerUtilMap.get(player.uuid).rank = 1;
                        allowCC(player);
                    }
                }
            }
            counter += Time.delta();
            lastMin = (int) Math.ceil((roundTime - counter) / 60 / 60);
        });

        Events.on(EventType.PlayerConnect.class, event -> {
            for(String swear : BannedNames.badNames){
                if(event.player.name.toLowerCase().contains(swear)){
                    event.player.name = event.player.name.replaceAll("(?i)" + swear, "");
                }
            }
        });

        Events.on(EventType.PlayerJoin.class, event -> {
            Call.setHudTextReliable(event.player.con, "[accent]Play time: [scarlet]" + playerUtilMap.get(event.player.uuid).playTime + "[accent] mins.");

            // Tile tile = world.tile(255, 255);
            if(event.player.getTeam() == Team.blue){
                event.player.setTeam(Team.crux);
                needsChanging.add(event.player.uuid);
            }
            event.player.sendMessage("[accent]Map: [scarlet]" + mapName + "\n[accent]Author: [scarlet]" + mapAuthor);

        });

        Events.on(EventType.PlayerLeave.class, event -> {
            if(event.player.getTeam() != Team.crux && teamSize(event.player.getTeam()) < 2) {
                killTiles(event.player.getTeam(), event.player);
            }
            if(event.player.getTeam() == Team.crux){
                infected --;
            }else if (event.player.getTeam() != Team.blue){
                survivors --;
            }

            lastTeam.put(event.player.uuid, event.player.getTeam());

            // Handle all database side of things

            try{
                playerDB.entries.get(event.player.uuid).put("playTime", playerUtilMap.get(event.player.uuid).playTime);
                playerDB.entries.get(event.player.uuid).put("rankLevel", playerUtilMap.get(event.player.uuid).rank);
                playerDB.entries.get(event.player.uuid).put("donateLevel", playerUtilMap.get(event.player.uuid).donateLevel);
                playerUtilMap.remove(event.player.uuid);
                playerDB.saveRow(event.player.uuid);
            }catch(NullPointerException ignore){}


        });

        Events.on(EventType.BuildSelectEvent.class, event ->{

            if(event.breaking && draugCount.containsKey(event.team) && draugCount.get(event.team).contains(event.tile)){
                draugCount.get(event.team).remove(event.tile);
            }

            if(event.breaking && eradTiles.contains(event.tile)){
                eradTiles.remove(event.tile);
                plagueErads -= 1;
            }

            if(event.team == Team.blue){
                event.tile.removeNet();
                if(Build.validPlace(event.team, event.tile.x, event.tile.y, Blocks.spectre, 0) && !event.breaking){ // Use spectre in place of core, as core always returns false
                    survivors ++;
                    // Check if the core is within 50 blocks of another core
                    final Team[] chosenTeam = {Team.all()[teams+6]};
                    teams ++;
                    final boolean[] breakLoop = {false};
                    state.teams.eachEnemyCore(event.team, core -> {
                        if(!breakLoop[0] && cartesianDistance(event.tile.x, event.tile.y, core.tile.x, core.tile.y) < 100 && core.getTeam() != Team.crux) {
                            chosenTeam[0] = core.getTeam();
                            teams--;
                            breakLoop[0] = true;
                        }
                    });

                    Player player = playerGroup.getByID(event.builder.getID());
                    player.setTeam(chosenTeam[0]);
                    if(player.isAdmin){
                        player.name = filterColor(player.name, "[green]");
                    }else{
                        player.name = filterColor(player.name, "[olive]");
                    }
                    event.tile.setNet(Blocks.coreFoundation, chosenTeam[0], 0);
                    state.teams.registerCore((CoreBlock.CoreEntity) event.tile.entity);
                    Log.info(state.teams.cores(chosenTeam[0]).size);
                    if (state.teams.cores(chosenTeam[0]).size == 1){
                        for(ItemStack stack : state.rules.loadout){
                            Call.transferItemTo(stack.item, stack.amount, event.tile.drawx(), event.tile.drawy(), event.tile);
                        }
                    }

                    player.setDead(true);
                    player.onRespawn(state.teams.cores(chosenTeam[0]).get(0).tile);

                    Call.onSetRules(player.con, survivorBanned);
                }
            }
        });

        Events.on(EventType.BlockBuildEndEvent.class, event -> {
            if(event.tile.block() == Blocks.draugFactory){
                if(draugCount.containsKey(event.team)){
                    draugCount.get(event.team).add(event.tile);
                }else{
                    draugCount.put(event.team, new LinkedList<>(Arrays.asList(event.tile)));
                }
            }
            if(event.tile.block() == Blocks.revenantFactory){
                plagueErads += 1;
                eradTiles.add(event.tile);
            }
        });

        Events.on(EventType.BlockDestroyEvent.class, event ->{
            if(eradTiles.contains(event.tile)){
                eradTiles.remove(event.tile);
                plagueErads -= 1;
            }
        });

    }

    @Override
    public void registerServerCommands(CommandHandler handler){
        handler.register("plague", "Begin hosting with the Plague gamemode.", args -> {
            if(!state.is(State.menu)){
                Log.err("Stop the server first.");
                return;
            }

            prefs = Preferences.userRoot().node(this.getClass().getName());
            int currMap = prefs.getInt("mapchoice",0);
            prefs.putInt("mapchoice", 0); // This is just backup so the server reverts to patient zero if a map crashes
            Log.info("Map choice: " + currMap);
            // currMap = 7;

            List<Integer> allMaps = new ArrayList<>();

            for(int i =0; i < maps.customMaps().size+1; i++){
                if(i!=currMap && maps.customMaps().size > 0) allMaps.add(i);
            }
            Collections.shuffle(allMaps);
            for(int i =0; i < voteSize; i++){
                int mapInd = allMaps.get(i);
                votableMaps.add(mapInd);
                if(mapInd == 0) mapList.add("Patient zero"); else mapList.add(maps.customMaps().get(mapInd-1).name());
                mapVotes.add(0);
            }

            String str = "";
            str += "[accent]Vote on the next map using /votemap, voting between [scarlet]1[accent] and [scarlet]" + votableMaps.size() + "[accent] on the following maps:\n";
            for(int i = 0; i < votableMaps.size(); i++){
                str += "[scarlet]" + (i+1) + "[white]: " + mapList.get(i) +  "\n";
            }
            announcements[2] = str;

            int i = 1;
            for(mindustry.maps.Map m : maps.customMaps()){
                Log.info(i + ": " + m.name());
                i ++;
            }



            logic.reset();
            if(currMap == 0){
                Log.info("Generating map...");
                PlagueGenerator generator = new PlagueGenerator();
                world.loadGenerator(generator);
                Log.info("Map generated.");
            }else{
                mindustry.maps.Map map = maps.customMaps().get(currMap-1);
                world.loadMap(map);
            }
            Tile tile = state.teams.cores(Team.crux).get(0).tile;
            plagueCore[0] = tile.x;
            plagueCore[1] = tile.y;
            PlagueGenerator.inverseFloodFill(world.getTiles(), plagueCore[0], plagueCore[1]);
            PlagueGenerator.defaultOres(world.getTiles());
            mapName = world.getMap().name();
            mapAuthor = world.getMap().author();

            Log.info("Current map: " + mapName);

            state.rules = rules.copy();
            logic.play();
            netServer.openServer();

            // Initialise creep
            creep = new boolean[world.width()][world.height()];
            for(int x = 0; x < world.width(); x++){
                for(int y = 0; y < world.height(); y++){
                    float dist = cartesianDistance(x, y, plagueCore[0], plagueCore[1]);
                    if(dist < world.height()/4 && !world.tile(x,y).block().isStatic()){
                        creep[x][y] = true;
                        if(dist+1 > world.height()/4) creepPerimeter.add(world.tile(x, y));
                    }
                }
            }

            for(ItemStack stack : state.rules.loadout){
                Call.transferItemTo(stack.item, stack.amount, tile.drawx(), tile.drawy(), tile);
            }
        });

        handler.register("setplaytime", "<uuid> <playtime>", "Set the play time of a player", args -> {
            int newTime;
            try{
                newTime = Integer.parseInt(args[1]);
            }catch(NumberFormatException e){
                Log.info("Invalid playtime input '" + args[1] + "'");
                return;
            }

            if(!playerDB.entries.containsKey(args[0])){
                playerDB.loadRow(args[0]);
                playerDB.entries.get(args[0]).put("playTime", newTime);
                playerDB.saveRow(args[0]);
            }else{
                Player player = playerUtilMap.get(args[0]).player;
                playerUtilMap.get(args[0]).playTime = newTime;
                Call.setHudTextReliable(player.con, "[accent]Play time: [scarlet]" + playerUtilMap.get(player.uuid).playTime + "[accent] mins.");
            }
            Log.info("Set uuid " + args[0] + " to have play time of " + args[1] + " minutes");

        });

        handler.register("setrank", "<uuid> <rank>", "Set the rank of a player", args -> {
            int newRank;
            try{
                newRank = Integer.parseInt(args[1]);
            }catch(NumberFormatException e){
                Log.info("Invalid prank input '" + args[1] + "'");
                return;
            }
            if(newRank < 0 || newRank > 4){
                Log.info("Invalid prank input '" + args[1] + "'");
                return;
            }

            if(!playerDB.entries.containsKey(args[0])){
                playerDB.loadRow(args[0]);
                playerDB.entries.get(args[0]).put("rankLevel", newRank);
                playerDB.saveRow(args[0]);
            }else{
                playerUtilMap.get(args[0]).rank = newRank;
            }
            Log.info("Set uuid " + args[0] + " to have rank of " + args[1]);

        });

        handler.register("setdonate", "<uuid> <level>", "Set the donateLevel of a player", args -> {
            int newRank;
            try{
                newRank = Integer.parseInt(args[1]);
            }catch(NumberFormatException e){
                Log.info("Invalid donateLevel input '" + args[1] + "'");
                return;
            }
            if(newRank < 0 || newRank > 4){
                Log.info("Invalid donateLevel input '" + args[1] + "'");
                return;
            }

            if(!playerDB.entries.containsKey(args[0])){
                playerDB.loadRow(args[0]);
                playerDB.entries.get(args[0]).put("donateLevel", newRank);
                playerDB.saveRow(args[0]);
            }else{
                playerUtilMap.get(args[0]).donateLevel = newRank;
            }
            Log.info("Set uuid " + args[0] + " to have donateLevel of " + args[1]);

        });

        handler.register("end", "End the game.", args -> endGame(true));

        handler.register("r", "Restart the server.", args -> System.exit(2));

    }

    @Override
    public void registerClientCommands(CommandHandler handler){
        if(registered) return;
        registered = true;

        handler.<Player>register("hub", "Connect to the AA hub server", (args, player) -> {
            Call.onConnect(player.con, "aamindustry.play.ai", 6567);
        });

        handler.<Player>register("discord", "Prints the discord link", (args, player) -> {
            player.sendMessage("[purple]https://discord.gg/GEnYcSv");
        });

        handler.<Player>register("info", "SShow info", (args, player) -> {
            player.sendMessage("1. Creep can be stopped with titanium or above walls\n" +
                    "2. There is no time limit\n" +
                    "3. Surge is now used by both plague and survivors to upgrade themselves.\n" +
                    "4. Surge can not be removed from the core\n" +
                    "5. Unloaders can not be placed near to core (currently bugged for survivors)\n" +
                    "6. Here are the upgrade lists for both teams:\n" +
                    "\n" +
                    "SURVIVORS:\n" +
                    "  Level 1 (500 Surge): Thorium and Plastanium walls\n" +
                    "  Level 2 (1000 Surge): Mend projector\n" +
                    "  Level 3 (2500 Surge): Spectre and meltdown (both of which DO NOT cost surge to build)\n" +
                    "  Level 4 (5000 Surge): Initiate 10 minute countdown until survivors win\n" +
                    "PLAGUE:\n" +
                    "  Level 1 (1000 Surge): None\n" +
                    "  Level 2 (1000 Surge): None\n" +
                    "  Level 3 (1000 Surge): None\n" +
                    "  Level 4 (1000 Surge): Fortress factories that spawn 1 Chaos array each\n" +
                    "  Level 5 (20000 Surge): E R A D I C A T O R (unlocks a revenant factory)\n" +
                    "  Level 6->9 (2000 Surge each): 1 additional revenant factory is allowed to be built");
        });

        handler.<Player>register("maps", "Show votable maps", (args, player) -> {
            player.sendMessage(announcements[2]);
        });

        handler.<Player>register("votemap", "<number>", "Vote for the next map", (args, player) -> {
            int vote;
            try{
                vote = Integer.parseInt(args[0]);
            }catch (NumberFormatException ignored){
                vote = -1;
            }

            if(vote < 1 || vote > votableMaps.size()){
                String str = "";
                str += "[accent]Your vote must be between [scarlet]1[accent] and [scarlet]" + votableMaps.size() + "[accent] on the following maps:\n";
                for(int i = 0; i < votableMaps.size(); i++){
                    str += "[scarlet]" + (i+1) + "[white]: " + mapList.get(i) +  " ([accent]" + mapVotes.get(i) + "[white])\n";
                }
                player.sendMessage(str);
                return;
            }
            vote -= 1;
            int votePower = 1;
            if(playerUtilMap.get(player.uuid).donateLevel != 0) votePower = 2;
            if(playerMapVote.containsKey(player.uuid)){
                int lastVote = playerMapVote.get(player.uuid);
                mapVotes.set(lastVote, mapVotes.get(lastVote)-votePower);
                playerMapVote.put(player.uuid, vote);
                mapVotes.set(vote, mapVotes.get(vote)+votePower);
                player.sendMessage("[accent]Changed vote from [scarlet]" + (lastVote+1) + "[accent] to [scarlet]" + (vote+1));
            }else{
                playerMapVote.put(player.uuid, vote);
                mapVotes.set(vote, mapVotes.get(vote)+votePower);
                player.sendMessage("[accent]You voted on map [scarlet]" + (vote+1));
            }

        });

        handler.<Player>register("uuid", "Prints your uuid", (args, player) -> {
            player.sendMessage("[accent]Your uuid is: [scarlet]" + player.uuid);
        });

        handler.<Player>register("time", "Display the time left", (args, player) -> {
            if(escaping){
                int min = escapeTicksLeft / 60 / 60 + 1;
                player.sendMessage("[accent]The [green]Survivors[accent] will escape in [scarlet]" + min + "[accent]" + (min > 1 ? " minutes" : " minute") + "!");
            }else{
                player.sendMessage("The survivors have not gotten enough surge to escape yet");
            }
        });

        handler.<Player>register("enemies", "List enemies", (args, player) -> {
            player.sendMessage(String.valueOf(player.getTeam().enemies()));
        });

        handler.<Player>register("whoami", "Prints your team", (args, player) -> {
            player.sendMessage(String.valueOf(player.getTeam()));
        });

        handler.<Player>register("block", "Prints the block you are currently on top off", (args, player) -> {
            Tile t = world.tile(player.tileX(), player.tileY());
            player.sendMessage("[accent]" + t.link().block().name);
        });

        handler.<Player>register("infect", "Infect yourself", (args, player) -> {
            Team plyTeam = player.getTeam();
            if(plyTeam != Team.crux){
                if(teamSize(plyTeam) < 2){
                    killTiles(plyTeam, player);
                }
                infect(player);
            }else{
                player.sendMessage("You are already infected!");
            }

        });

        handler.<Player>register("erads", "Show the current plague erad count and cap", (args, player) -> {
            player.sendMessage(plagueErads + " / " + plagueEradCap);

        });

        handler.<Player>register("endgame", "End the game.", (args, player) -> {
            if(player.isAdmin){
                endGame(true);
            }else{
                player.sendMessage("[accent]You do not have the required permissions to run this command");
            }
        });

    }

    void init_rules(){

        survivorBanned = rules.copy();
        survivorBanned.bannedBlocks.addAll(Blocks.commandCenter, Blocks.wraithFactory, Blocks.ghoulFactory, Blocks.revenantFactory, Blocks.daggerFactory,
                Blocks.crawlerFactory, Blocks.titanFactory, Blocks.fortressFactory);

        // The below are blocks that can be unlocked with surge

        survivorBanned.bannedBlocks.addAll(Blocks.thoriumWall, Blocks.thoriumWallLarge, Blocks.plastaniumWall, Blocks.plastaniumWallLarge,
                Blocks.spectre, Blocks.meltdown, Blocks.mendProjector);

        plagueBanned = rules.copy();
        plagueBanned.bannedBlocks.add(Blocks.commandCenter); // Can't be trusted
        plagueBanned.bannedBlocks.addAll(Blocks.duo, Blocks.scatter, Blocks.scorch, Blocks.lancer, Blocks.arc, Blocks.swarmer, Blocks.salvo,
                Blocks.fuse, Blocks.cyclone, Blocks.spectre, Blocks.meltdown, Blocks.hail, Blocks.ripple, Blocks.shockMine);

        // Add power blocks to this because apparantly people don't know how to not build power blocks

        plagueBanned.bannedBlocks.addAll(Blocks.battery, Blocks.batteryLarge, Blocks.combustionGenerator, Blocks.thermalGenerator,
                Blocks.turbineGenerator, Blocks.differentialGenerator, Blocks.rtgGenerator, Blocks.solarPanel, Blocks.largeSolarPanel,
                Blocks.thoriumReactor, Blocks.impactReactor);

        plagueBanned.bannedBlocks.addAll(Blocks.surgeWall, Blocks.surgeWallLarge, Blocks.thoriumWall, Blocks.thoriumWallLarge, Blocks.phaseWall,
                Blocks.phaseWallLarge, Blocks.titaniumWall, Blocks.titaniumWallLarge, Blocks.copperWallLarge, Blocks.copperWall, Blocks.door,
                Blocks.doorLarge, Blocks.plastaniumWall, Blocks.plastaniumWallLarge);

        plagueBanned.bannedBlocks.addAll(Blocks.mendProjector);

        // The below are blocks that can be unlocked with surge

        plagueBanned.bannedBlocks.addAll(Blocks.crawlerFactory, Blocks.daggerFactory, Blocks.titanFactory, Blocks.fortressFactory, Blocks.revenantFactory);

        Blocks.powerSource.health = Integer.MAX_VALUE;

        Block drauge = Vars.content.blocks().find(block -> block.name.equals("draug-factory"));
        ((UnitFactory)(drauge)).maxSpawn = 0; // Should always be 0


        Block dagger = Vars.content.blocks().find(block -> block.name.equals("dagger-factory"));
        ((UnitFactory)(dagger)).unitType = UnitTypes.fortress;
        ((UnitFactory)(dagger)).maxSpawn = 1; // 1

        Block crawler = Vars.content.blocks().find(block -> block.name.equals("crawler-factory"));
        ((UnitFactory)(crawler)).unitType = UnitTypes.dagger;
        ((UnitFactory)(crawler)).maxSpawn = 2; // 2

        Block titan = Vars.content.blocks().find(block -> block.name.equals("titan-factory"));
        ((UnitFactory)(titan)).unitType = UnitTypes.eruptor;
        ((UnitFactory)(titan)).maxSpawn = 2; // 2

        UnitTypes.eruptor.health *= 2;

        Block fortress = Vars.content.blocks().find(block -> block.name.equals("fortress-factory"));
        ((UnitFactory)(fortress)).unitType = UnitTypes.chaosArray;
        ((UnitFactory)(fortress)).produceTime *= 2;
        ((UnitFactory)(fortress)).maxSpawn = 1; // 1

        Block rev = Vars.content.blocks().find(block -> block.name.equals("revenant-factory"));
        ((UnitFactory)(rev)).unitType = UnitTypes.eradicator;
        ((UnitFactory)(rev)).produceTime = 1;
        ((UnitFactory)(rev)).maxSpawn = 1; // 1

        // Disable slag flammability to prevent griefing
        Liquids.slag.flammability = 0;
        Liquids.slag.explosiveness = 0;

        UnitTypes.chaosArray.weapon = PlagueData.nerfedChaos();

        Blocks.phaseWall.health /= 2;
        Blocks.phaseWallLarge.health /= 2;

        Mechs.tau.itemCapacity = 0;
        Mechs.trident.drillPower = 1;
        Mechs.omega.drillPower = 1;
        // Update phase wall to only deflect 50% of the time
        /*Block phaseSmall = Vars.content.blocks().find(block -> block.name.equals("phase-wall"));
        Vars.content.blocks().remove(phaseSmall);
        phaseSmall = PlagueData.newPhaseSmall();
        Vars.content.blocks().add(phaseSmall);*/

        /*Block phaseLarge = Vars.content.blocks().find(block -> block.name.equals("phase-wall-large"));
        Vars.content.blocks().remove(phaseLarge);
        phaseLarge = PlagueData.newPhaseLarge();
        Vars.content.blocks().add(phaseLarge);*/


        for(Block block : content.blocks()){
            for(ItemStack is : block.requirements){
                if(is.item == Items.surgealloy){
                    is.amount = 0;
                }
            }
        }
    }

    void plagueUnlock(int level){
        if(level == 1){
            Call.sendMessage("[accent]Plague reached level 1. At level 3 they will unlock fortress factories that make chaos arrays");
            //plagueBanned.bannedBlocks.remove(Blocks.crawlerFactory);
        }
        if(level == 2){
            Call.sendMessage("[accent]Plague reached level 2. At level 3 they will unlock fortress factories that make chaos arrays");
            //plagueBanned.bannedBlocks.remove(Blocks.daggerFactory);
        }
        if(level == 3){
            Call.sendMessage("[accent]Plague reached level 3 and unlocked fortress factories that make chaos arrays");
            plagueBanned.bannedBlocks.remove(Blocks.fortressFactory);
            //plagueBanned.bannedBlocks.remove(Blocks.titanFactory);
        }
        if(level == 4){
            Call.sendMessage("[accent]Plague reached level 4. In one more level they can make revenant factories that make eradicators");
            //plagueBanned.bannedBlocks.remove(Blocks.fortressFactory);
        }
        if(level == 5){
            Call.sendMessage("[accent]The ground rumbles as the [scarlet]Plague [accent]unearths something terrifying[white] \uF84D");
            plagueBanned.bannedBlocks.remove(Blocks.revenantFactory);
            plagueEradCap = 1;
        }
        if(level > 5){
            Call.sendMessage("[accent]The plague unlocked an additional eradicator");
            plagueEradCap += 1;
        }



        for(Player ply : playerGroup.all()){
            CustomPlayer p = playerUtilMap.get(ply.uuid);
            if(p == null) continue;
            if(!p.infected) continue;
            if(p.rank == 0 && p.donateLevel == 0){
                Call.onSetRules(ply.con, plagueBanned);
            }else{
                allowCC(ply);
            }
        }
    }

    void survivorUnlock(int level){
        if(level == 1){
            survivorBanned.bannedBlocks.remove(Blocks.thoriumWall);
            survivorBanned.bannedBlocks.remove(Blocks.thoriumWallLarge);
            survivorBanned.bannedBlocks.remove(Blocks.plastaniumWall);
            survivorBanned.bannedBlocks.remove(Blocks.plastaniumWallLarge);
        }
        if(level == 2){
            survivorBanned.bannedBlocks.remove(Blocks.mendProjector);
        }
        if(level == 3){
            survivorBanned.bannedBlocks.remove(Blocks.spectre);
            survivorBanned.bannedBlocks.remove(Blocks.meltdown);
        }
        if(level == 4 && !escaping){
            escaping = true;
            Call.onInfoMessage("[accent]A group of [green]Survivors[accent] have collected enough surge to escape the planet!\n[white]The [green]Survivors [white]must survive just [scarlet]" + (escapeTicksLeft/60/60) + " [white]more minutes while their rocket launches!");
            Call.sendMessage("[accent]The vibrations of the rocket have allowed the [scarlet]Plague [accent] to make 2 additional eradicators!");
            plagueEradCap += 2;
        }

        for(Player ply : playerGroup.all()){
            CustomPlayer p = playerUtilMap.get(ply.uuid);
            if(p == null) continue;
            if(p.infected) continue;
            Call.onSetRules(ply.con, survivorBanned);
        }
    }

    int teamSize(Team t){
        int size = 0;
        for(Player player : playerGroup.all()){
            if (player.getTeam() == t) size ++;
        }
        return size;
    }

    void infect(Player player){

        infected ++;
        if(player.getTeam() != Team.blue) survivors --;
        Call.sendMessage("[accent]" + player.name + "[white] was [red]infected[white]!");
        if(teamSize(player.getTeam()) < 2) killTiles(player.getTeam(), player);
        player.setTeam(Team.crux);
        if(player.isAdmin){
            player.name = filterColor(player.name, "[red]");
        }else{
            player.name = filterColor(player.name, "[scarlet]");
        }
        player.kill();
        playerUtilMap.get(player.uuid).infected = true;
        if(playerUtilMap.get(player.uuid).donateLevel != 0 || playerUtilMap.get(player.uuid).rank != 0){
            allowCC(player);
        }else {
            Call.onSetRules(player.con, plagueBanned);
        }
    }

    void allowCC(Player player, boolean override){
        if(playerUtilMap.get(player.uuid).infected || override){
            Rules temp = plagueBanned.copy();
            temp.bannedBlocks.remove(Blocks.commandCenter);
            Call.onSetRules(player.con, temp);
        }
    }

    void allowCC(Player player){
        this.allowCC(player, false);
    }

    void expandCreep(){
        List<Tile> newList = new ArrayList<>();
        for(Tile t : creepPerimeter){
            if(t.x+1 < world.width() && !creep[t.x+1][t.y] && !world.tile(t.x+1,t.y).block().isStatic() && !world.tile(t.x+1,t.y).floor().isDeep()){
                if(creepStoppers.contains(world.tile(t.x+1, t.y).link().block().name)){
                    if(!newList.contains(t)) newList.add(t);
                }else{
                    newList.add(world.tile(t.x+1, t.y));
                    creep[t.x+1][t.y] = true;
                }

            }
            if(t.x-1 >= 0 && !creep[t.x-1][t.y] && !world.tile(t.x-1,t.y).block().isStatic() && !world.tile(t.x-1,t.y).floor().isDeep()){
                if(creepStoppers.contains(world.tile(t.x-1, t.y).link().block().name)){
                    if(!newList.contains(t)) newList.add(t);
                }else{
                    newList.add(world.tile(t.x-1, t.y));
                    creep[t.x-1][t.y] = true;
                }
            }
            if(t.y+1 < world.height() && !creep[t.x][t.y+1] && !world.tile(t.x,t.y+1).block().isStatic() && !world.tile(t.x,t.y+1).floor().isDeep()){
                if(creepStoppers.contains(world.tile(t.x, t.y+1).link().block().name)){
                    if(!newList.contains(t)) newList.add(t);
                }else{
                    newList.add(world.tile(t.x, t.y+1));
                    creep[t.x][t.y+1] = true;
                }
            }
            if(t.y-1 >= 0 && !creep[t.x][t.y-1] && !world.tile(t.x,t.y-1).block().isStatic() && !world.tile(t.x,t.y-1).floor().isDeep()){
                if(creepStoppers.contains(world.tile(t.x, t.y-1).link().block().name)){
                    if(!newList.contains(t)) newList.add(t);
                }else{
                    newList.add(world.tile(t.x, t.y-1));
                    creep[t.x][t.y-1] = true;
                }
            }
        }
        creepPerimeter = newList;
    }

    void endGame(boolean survivorWin){
        if(restarting) return;


        for(Player player: playerGroup.all()){
            if(survivorWin){
                Call.onInfoMessage(player.con, "[accent]--ROUND OVER--\n\n[green]Survivors[lightgray] win!");
            }else{
                Call.onInfoMessage(player.con, "[accent]--ROUND OVER--\n\n[red]Plague[lightgray] wins!");
            }
        }
        restarting = true;
        Log.info("&ly--SERVER RESTARTING--");

        int votedMap = mapVotes.indexOf(Collections.max(mapVotes));

        prefs = Preferences.userRoot().node(this.getClass().getName());
        prefs.putInt("mapchoice", votableMaps.get(votedMap));
        Call.sendMessage("[accent]Loading map [scarlet]" + mapList.get(votedMap) + "[accent] with the most votes");

        Time.runTask(60f * 10f, () -> {
            for(Player player : playerGroup.all()) {
                Call.onConnect(player.con, "aamindustry.play.ai", 6567);
                playerDB.entries.get(player.uuid).put("playTime", playerUtilMap.get(player.uuid).playTime);
                playerDB.entries.get(player.uuid).put("rankLevel", playerUtilMap.get(player.uuid).rank);


                playerUtilMap.remove(player.uuid);
                playerDB.saveRow(player.uuid);
                //player.con.close();
            }
            // I shouldn't need this, all players should be gone since I connected them to hub
            // netServer.kickAll(KickReason.serverRestarting);
            Time.runTask(5f, () -> System.exit(2));
        });
    }


    void killTiles(Team team, Player player){
        for(int x = 0; x < world.width(); x++){
            for(int y = 0; y < world.height(); y++){
                Tile tile = world.tile(x, y);
                if(tile.entity != null && tile.getTeam() == team){
                    Time.run(Mathf.random(60f * 6), tile.entity::kill);
                }
            }
        }
    }

    public String filterColor(String s, String prefix){
        return prefix + Strings.stripColors(s);
    }

    public boolean active(){
        return state.rules.tags.getBool("plague") && !state.is(State.menu);
    }

    private float cartesianDistance(float x, float y, float cx, float cy){
        return (float) Math.sqrt(Math.pow(x - cx, 2) + Math.pow(y - cy, 2) );
    }
}