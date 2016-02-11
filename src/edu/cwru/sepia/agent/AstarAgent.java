package edu.cwru.sepia.agent;

import edu.cwru.sepia.action.Action;
import edu.cwru.sepia.environment.model.history.History;
import edu.cwru.sepia.environment.model.state.ResourceNode;
import edu.cwru.sepia.environment.model.state.State;
import edu.cwru.sepia.environment.model.state.Unit;
import edu.cwru.sepia.util.Direction;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

public class AstarAgent extends Agent {

    class MapLocation
    {
        public int x, y;

        public MapLocation(int x, int y, MapLocation cameFrom, float cost)
        {
            this.x = x;
            this.y = y;
        }

        public int hashCode() {
            int hash = 17;
            hash = 31 * hash + x;
            hash = 31 * hash + y;
            return hash;
        }

        // returns if two MapLocations are the same
        @Override
        public boolean equals(Object obj) {

            if (obj == null) {
                return this == null;
            }

            if (obj instanceof MapLocation) {
                MapLocation loc = (MapLocation) obj;

                return this.x == loc.x && this.y == loc.y;
            }

            return false;
        }

        // A String representaion of a MapLocation
        @Override
        public String toString() {
            return "(" + x + ", " + y + ")";
        }

    }

    int timesBlocked = 0;
    Stack<MapLocation> oldPath = null;

    Stack<MapLocation> path;
    int footmanID, townhallID, enemyFootmanID;
    MapLocation nextLoc;

    private long totalPlanTime = 0; // nsecs
    private long totalExecutionTime = 0; //nsecs

    public AstarAgent(int playernum)
    {
        super(playernum);

        System.out.println("Constructed AstarAgent");
    }

    @Override
    public Map<Integer, Action> initialStep(State.StateView newstate, History.HistoryView statehistory) {
        // get the footman location
        List<Integer> unitIDs = newstate.getUnitIds(playernum);

        if(unitIDs.size() == 0)
        {
            System.err.println("No units found!");
            return null;
        }

        footmanID = unitIDs.get(0);

        // double check that this is a footman
        if(!newstate.getUnit(footmanID).getTemplateView().getName().equals("Footman"))
        {
            System.err.println("Footman unit not found");
            return null;
        }

        // find the enemy playernum
        Integer[] playerNums = newstate.getPlayerNumbers();
        int enemyPlayerNum = -1;
        for(Integer playerNum : playerNums)
        {
            if(playerNum != playernum) {
                enemyPlayerNum = playerNum;
                break;
            }
        }

        if(enemyPlayerNum == -1)
        {
            System.err.println("Failed to get enemy playernumber");
            return null;
        }

        // find the townhall ID
        List<Integer> enemyUnitIDs = newstate.getUnitIds(enemyPlayerNum);

        if(enemyUnitIDs.size() == 0)
        {
            System.err.println("Failed to find enemy units");
            return null;
        }

        townhallID = -1;
        enemyFootmanID = -1;
        for(Integer unitID : enemyUnitIDs)
        {
            Unit.UnitView tempUnit = newstate.getUnit(unitID);
            String unitType = tempUnit.getTemplateView().getName().toLowerCase();
            if(unitType.equals("townhall"))
            {
                townhallID = unitID;
            }
            else if(unitType.equals("footman"))
            {
                enemyFootmanID = unitID;
            }
            else
            {
                System.err.println("Unknown unit type");
            }
        }

        if(townhallID == -1) {
            System.err.println("Error: Couldn't find townhall");
            return null;
        }

        long startTime = System.nanoTime();
        path = findPath(newstate);
        totalPlanTime += System.nanoTime() - startTime;

        return middleStep(newstate, statehistory);
    }

    @Override
    public Map<Integer, Action> middleStep(State.StateView newstate, History.HistoryView statehistory) {
        long startTime = System.nanoTime();
        long planTime = 0;

        Map<Integer, Action> actions = new HashMap<Integer, Action>();

        if(shouldReplanPath(newstate, statehistory, path)) {
            long planStartTime = System.nanoTime();
            path = findPath(newstate);
            planTime = System.nanoTime() - planStartTime;
            totalPlanTime += planTime;
        }

        Unit.UnitView footmanUnit = newstate.getUnit(footmanID);

        int footmanX = footmanUnit.getXPosition();
        int footmanY = footmanUnit.getYPosition();

        if(!path.empty() && (nextLoc == null || (footmanX == nextLoc.x && footmanY == nextLoc.y))) {

            // stat moving to the next step in the path
            nextLoc = path.pop();

            System.out.println("Moving to (" + nextLoc.x + ", " + nextLoc.y + ")");
        }

        if(nextLoc != null && (footmanX != nextLoc.x || footmanY != nextLoc.y))
        {
            int xDiff = nextLoc.x - footmanX;
            int yDiff = nextLoc.y - footmanY;

            // figure out the direction the footman needs to move in
            Direction nextDirection = getNextDirection(xDiff, yDiff);

            actions.put(footmanID, Action.createPrimitiveMove(footmanID, nextDirection));
        } else {
            Unit.UnitView townhallUnit = newstate.getUnit(townhallID);

            // if townhall was destroyed on the last turn
            if(townhallUnit == null) {
                terminalStep(newstate, statehistory);
                return actions;
            }

            if(Math.abs(footmanX - townhallUnit.getXPosition()) > 1 ||
                    Math.abs(footmanY - townhallUnit.getYPosition()) > 1)
            {
                System.err.println("Invalid plan. Cannot attack townhall");
                totalExecutionTime += System.nanoTime() - startTime - planTime;
                return actions;
            }
            else {
                System.out.println("Attacking TownHall");
                // if no more movements in the planned path then attack
                actions.put(footmanID, Action.createPrimitiveAttack(footmanID, townhallID));
            }
        }

        totalExecutionTime += System.nanoTime() - startTime - planTime;
        return actions;
    }

    @Override
    public void terminalStep(State.StateView newstate, History.HistoryView statehistory) {
        System.out.println("Total turns: " + newstate.getTurnNumber());
        System.out.println("Total planning time: " + totalPlanTime/1e9);
        System.out.println("Total execution time: " + totalExecutionTime/1e9);
        System.out.println("Total time: " + (totalExecutionTime + totalPlanTime)/1e9);
    }

    @Override
    public void savePlayerData(OutputStream os) {

    }

    @Override
    public void loadPlayerData(InputStream is) {

    }

    /**
     * You will implement this method.
     *
     * This method should return true when the path needs to be replanned
     * and false otherwise. This will be necessary on the dynamic map where the
     * footman will move to block your unit.
     *
     * @param state
     * @param history
     * @param currentPath
     * @return
     */
    private boolean shouldReplanPath(State.StateView state, History.HistoryView history, Stack<MapLocation> currentPath)
    {

        MapLocation footmanLoc = null;
        if(enemyFootmanID != -1) {
            Unit.UnitView enemyFootmanUnit = state.getUnit(enemyFootmanID);
            footmanLoc = new MapLocation(enemyFootmanUnit.getXPosition(), enemyFootmanUnit.getYPosition(), null, 0);
        }
        
        if (oldPath != null && 
            oldPath.size() < currentPath.size() && 
            !oldPath.contains(footmanLoc)) {
            oldPath = currentPath;
            return true;
        }

        
        if (footmanLoc != null && currentPath.contains(footmanLoc)) {
            //timesBlocked++;
            
            oldPath = currentPath;
            return true;
            /*
            if ( timesBlocked > 2) {
                oldPath = currentPath;
                timesBlocked = 0;
                return true;
            }
            */
        }
        /*
        if (!currentPath.isEmpty()) {
            if (currentPath.peek().equals(footmanLoc)) {
                return true;
            }
        }
        */
            
        return false;
    }

    /**
     * This method is implemented for you. You should look at it to see examples of
     * how to find units and resources in Sepia.
     *
     * @param state
     * @return
     */
    private Stack<MapLocation> findPath(State.StateView state)
    {
        Unit.UnitView townhallUnit = state.getUnit(townhallID);
        Unit.UnitView footmanUnit = state.getUnit(footmanID);

        MapLocation startLoc = new MapLocation(footmanUnit.getXPosition(), footmanUnit.getYPosition(), null, 0);

        MapLocation goalLoc = new MapLocation(townhallUnit.getXPosition(), townhallUnit.getYPosition(), null, 0);

        MapLocation footmanLoc = null;
        if(enemyFootmanID != -1) {
            Unit.UnitView enemyFootmanUnit = state.getUnit(enemyFootmanID);
            footmanLoc = new MapLocation(enemyFootmanUnit.getXPosition(), enemyFootmanUnit.getYPosition(), null, 0);
        }

        // get resource locations
        List<Integer> resourceIDs = state.getAllResourceIds();
        Set<MapLocation> resourceLocations = new HashSet<MapLocation>();
        for(Integer resourceID : resourceIDs)
        {
            ResourceNode.ResourceView resource = state.getResourceNode(resourceID);

            resourceLocations.add(new MapLocation(resource.getXPosition(), resource.getYPosition(), null, 0));
        }

        return AstarSearch(startLoc, goalLoc, state.getXExtent(), state.getYExtent(), footmanLoc, resourceLocations);
    }
    /**
     * This is the method you will implement for the assignment. Your implementation
     * will use the A* algorithm to compute the optimum path from the start position to
     * a position adjacent to the goal position.
     *
     * You will return a Stack of positions with the top of the stack being the first space to move to
     * and the bottom of the stack being the last space to move to. If there is no path to the townhall
     * then return null from the method and the agent will print a message and do nothing.
     * The code to execute the plan is provided for you in the middleStep method.
     *
     * As an example consider the following simple map
     *
     * F - - - -
     * x x x - x
     * H - - - -
     *
     * F is the footman
     * H is the townhall
     * x's are occupied spaces
     *
     * xExtent would be 5 for this map with valid X coordinates in the range of [0, 4]
     * x=0 is the left most column and x=4 is the right most column
     *
     * yExtent would be 3 for this map with valid Y coordinates in the range of [0, 2]
     * y=0 is the top most row and y=2 is the bottom most row
     *
     * resourceLocations would be {(0,1), (1,1), (2,1), (4,1)}
     *
     * The path would be
     *
     * (1,0)
     * (2,0)
     * (3,1)
     * (2,2)
     * (1,2)
     *
     * Notice how the initial footman position and the townhall position are not included in the path stack
     *
     * @param start Starting position of the footman
     * @param goal MapLocation of the townhall
     * @param xExtent Width of the map
     * @param yExtent Height of the map
     * @param resourceLocations Set of positions occupied by resources
     * @return Stack of positions with top of stack being first move in plan
     */
    private Stack<MapLocation> AstarSearch(MapLocation start, MapLocation goal, int xExtent, int yExtent, MapLocation enemyFootmanLoc, Set<MapLocation> resourceLocations)
    {

        // search Lists
        Comparator comparator = pathComparator(start, goal);
        PriorityQueue<Stack<MapLocation>> openList = 
            new PriorityQueue<Stack<MapLocation>>(11, comparator);

        ArrayList<MapLocation> closedList = new ArrayList<MapLocation>();
        Stack<MapLocation> finalPath = new Stack<MapLocation>();
        Stack<MapLocation> initialPath = new Stack<MapLocation>();

        initialPath.add(start);
        openList.add(initialPath);

        // The search
        while (!openList.isEmpty()) {

            Stack<MapLocation> currentPath = openList.poll();
            MapLocation currentLoc = currentPath.peek(); 

            if (currentLoc.equals(goal)) {
                // found the goal
                currentPath.pop();
                while (!currentPath.isEmpty()) {
                    finalPath.push(currentPath.pop());
                }
                break;
            } else {
                // didn't find the goal
                MapLocation[] successors = 
                    expandState(currentLoc, goal, xExtent, yExtent);

                closedList.add(currentLoc);

                for (MapLocation successor : successors) {

                    // System.out.println(successor + " : " + enemyFootmanLoc);

                    if (successor != null  && !closedList.contains(successor) &&
                        !resourceLocations.contains(successor) && 
                        !alreadyPath(openList, successor) && 
                        !successor.equals(enemyFootmanLoc)) { 

                        Stack<MapLocation> newPath = 
                            (Stack<MapLocation>)currentPath.clone();
                        newPath.push(successor);
                        openList.add(newPath);
                    } 
                }

            }
        }

        if (finalPath.isEmpty()) {
            System.out.println("No Avaliable path");
            System.exit(0);
        }

        // return the path
        return finalPath;
    }

    /**
     * returns a comparator for lists of locations.
     * 
     * @return the comparator
     */
    private Comparator pathComparator(MapLocation start, MapLocation goal) {

        // class that compares two MapLocations by their heuristic
        class PathComparator implements Comparator<Stack<MapLocation>> {

            MapLocation start;
            MapLocation goal;

            public PathComparator(MapLocation start, MapLocation goal) {
                this.start = start;
                this.goal = goal;
            }

            public int compare(Stack<MapLocation> o1, Stack<MapLocation> o2) {
    
                int dist1 = o1.size();
                int dist2 = o2.size();
                int chebyshev1 = chebyshev(o1.peek(), goal);
                int chebyshev2 = chebyshev(o2.peek(), goal);

                if (chebyshev1 + dist1 == chebyshev2 + dist2) {
                    return 0;
                }

                return  dist1 + chebyshev1 < dist2 + chebyshev2 ? -1 : 1;
                
            }

            public boolean equals(Object obj) {
                return false; 
            }
        }

        return new PathComparator(start, goal);
    }

    /**
     * returns all of the successor nodes of a MapLocation
     *
     * @param location
     * @return the successor nodes
     */
    private MapLocation[] expandState(MapLocation loc, MapLocation goal, int xExtent, int yExtent) {

        MapLocation north       = new MapLocation(loc.x, loc.y - 1, null, 0);
        MapLocation south       = new MapLocation(loc.x, loc.y + 1, null, 0);
        MapLocation west        = new MapLocation(loc.x - 1, loc.y, null, 0);
        MapLocation east        = new MapLocation(loc.x + 1, loc.y, null, 0);
        MapLocation northWest   = new MapLocation(loc.x - 1, loc.y - 1, null, 0);
        MapLocation northEast   = new MapLocation(loc.x + 1, loc.y - 1, null, 0);
        MapLocation southWest   = new MapLocation(loc.x - 1, loc.y + 1, null, 0);
        MapLocation southEast   = new MapLocation(loc.x + 1, loc.y + 1, null, 0);
        MapLocation[] successors = {north, south, east, west,
                                    northEast, northWest, southEast, southWest};

        for (int i = 0; i < successors.length; i++) { 
            if ( successors[i].x < 0 || successors[i].x > xExtent ||
                 successors[i].y < 0 || successors[i].y > yExtent) {

                successors[i] = null;
            }
        }

        return successors;
    }

    /**
     * Calculates the Chebyshev distance
     *
     * @param start the node we are one
     * @param goal  the node we want to get to
     * @return      the Chebyshev distance
     */
    private int chebyshev(MapLocation start, MapLocation goal) {

        int deltaX = goal.x - start.x;
        int deltaY = goal.y - start.y;

        deltaX = deltaX < 0 ? deltaX * -1 : deltaX;
        deltaY = deltaY < 0 ? deltaY * -1 : deltaY;
        
        return deltaX < deltaY ? deltaY : deltaX;
    }

    /**
     * Calculates the minimum chebyshev distance
     *
     * @param start the node we are on
     * @param goal  the node we want to get to
     * @return      the minimum chebyshev distance
     */
    private int minChebyshev(MapLocation start, MapLocation goal) {
        
        int deltaX = goal.x - start.x;
        int deltaY = goal.y - start.y;

        deltaX = deltaX < 0 ? deltaX * -1 : deltaX;
        deltaY = deltaY < 0 ? deltaY * -1 : deltaY;
        
        return deltaX < deltaY ? deltaX : deltaY;
    }

    /**
     * Checks the open list to see if a path already has reached the location
     *
     * @param openList  the open list
     * @param location  the location
     * @return          Whether there is already a path
     */
    private boolean alreadyPath(PriorityQueue<Stack<MapLocation>> openList, MapLocation location) {
        for (Stack<MapLocation> path : openList) {
            if (path.peek().equals(location)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Primitive actions take a direction (e.g. NORTH, NORTHEAST, etc)
     * This converts the difference between the current position and the
     * desired position to a direction.
     *
     * @param xDiff Integer equal to 1, 0 or -1
     * @param yDiff Integer equal to 1, 0 or -1
     * @return A Direction instance (e.g. SOUTHWEST) or null in the case of error
     */
    private Direction getNextDirection(int xDiff, int yDiff) {

        // figure out the direction the footman needs to move in
        if(xDiff == 1 && yDiff == 1)
        {
            return Direction.SOUTHEAST;
        }
        else if(xDiff == 1 && yDiff == 0)
        {
            return Direction.EAST;
        }
        else if(xDiff == 1 && yDiff == -1)
        {
            return Direction.NORTHEAST;
        }
        else if(xDiff == 0 && yDiff == 1)
        {
            return Direction.SOUTH;
        }
        else if(xDiff == 0 && yDiff == -1)
        {
            return Direction.NORTH;
        }
        else if(xDiff == -1 && yDiff == 1)
        {
            return Direction.SOUTHWEST;
        }
        else if(xDiff == -1 && yDiff == 0)
        {
            return Direction.WEST;
        }
        else if(xDiff == -1 && yDiff == -1)
        {
            return Direction.NORTHWEST;
        }

        System.err.println("Invalid path. Could not determine direction");
        return null;
    }
}
