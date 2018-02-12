package igrek.robopath.simulation.lra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import igrek.robopath.common.Point;
import igrek.robopath.common.TileMap;
import igrek.robopath.mazegenerator.MazeGenerator;
import igrek.robopath.pathfinder.astar.Astar2DPathFinder;
import igrek.robopath.pathfinder.astar.Path;

public class LRAController {
	
	private Logger logger = LoggerFactory.getLogger(this.getClass());
	private Random random;
	private MazeGenerator mazegen;
	
	private TileMap map;
	private List<MobileRobot> robots = new ArrayList<>();
	private LRASimulationParams params;
	
	public LRAController(LRAPresenter presenter, LRASimulationParams params) {
		this.params = params;
		resetMap();
	}
	
	@Autowired
	public void setRandom(Random random) {
		this.random = random;
	}
	
	@Autowired
	public void setMazegen(MazeGenerator mazegen) {
		this.mazegen = mazegen;
	}
	
	public TileMap getMap() {
		return map;
	}
	
	public List<MobileRobot> getRobots() {
		return robots;
	}
	
	synchronized void resetMap() {
		map = new TileMap(params.mapSizeW, params.mapSizeH);
		robots.clear();
	}
	
	public synchronized void placeRobots() {
		robots.clear();
		for (int i = 0; i < params.robotsCount; i++) {
			Point cell = randomUnoccupiedCellForRobot(map);
			createMobileRobot(cell, i);
		}
	}
	
	public synchronized MobileRobot createMobileRobot(Point point, int i) {
		MobileRobot robo = new MobileRobot(point, robot -> onTargetReached(robot), i);
		robots.add(robo);
		return robo;
	}
	
	private void onTargetReached(MobileRobot robot) {
		if (params.robotAutoTarget) {
			if (robot.getTarget() == null || robot.hasReachedTarget()) {
				logger.debug("robot: " + robot.getPriority() + " - assigning new target");
				randomRobotTarget(robot);
			}
		}
	}
	
	public void randomTargetPressed() {
		for (MobileRobot robot : robots) {
			robot.setTarget(null); // clear targets - not to block each other during randoming
		}
		for (MobileRobot robot : robots) {
			randomRobotTarget(robot);
		}
	}
	
	public synchronized void generateMaze() {
		mazegen.generateMaze(map);
	}
	
	MobileRobot occupiedByRobot(Point point) {
		for (MobileRobot robot : robots) {
			if (robot.getPosition().equals(point))
				return robot;
		}
		return null;
	}
	
	private void randomRobotTarget(MobileRobot robot) {
		robot.resetNextMoves();
		//		Point start = robot.lastTarget();
		Point target = randomUnoccupiedCellForTarget(map);
		robot.setTarget(target);
	}
	
	private Point randomUnoccupiedCellForTarget(TileMap map) {
		// get all unoccupied cells
		List<Point> frees = new ArrayList<>();
		map.foreach((x, y, occupied) -> {
			if (!occupied)
				frees.add(new Point(x, y));
		});
		// remove occupied by other targets
		for (MobileRobot robot : robots) {
			Point target = robot.getTarget();
			if (target != null)
				frees.remove(target);
		}
		if (frees.isEmpty())
			return null;
		// random from list
		return frees.get(random.nextInt(frees.size()));
	}
	
	private Point randomUnoccupiedCellForRobot(TileMap map) {
		// get all unoccupied cells
		List<Point> frees = new ArrayList<>();
		map.foreach((x, y, occupied) -> {
			if (!occupied)
				frees.add(new Point(x, y));
		});
		// remove occupied by other robots
		for (MobileRobot robot : robots) {
			Point p = robot.getPosition();
			if (p != null)
				frees.remove(p);
		}
		if (frees.isEmpty())
			return null;
		// random from list
		return frees.get(random.nextInt(frees.size()));
	}
	
	synchronized void findPaths() {
		for (MobileRobot robot : robots) {
			findPath(robot);
		}
	}
	
	private void findPath(MobileRobot robot) {
		logger.debug("robot: " + robot.getPriority() + " - planning path");
		robot.resetMovesQue();
		Point start = robot.getPosition();
		Point target = robot.getTarget();
		if (target != null && !target.equals(start)) {
			TileMap map2 = mapWithRobots();
			Astar2DPathFinder pathFinder = new Astar2DPathFinder(map2);
			Path path = pathFinder.findPath(start.getX(), start.getY(), target.getX(), target.getY());
			if (path != null) {
				for (int i = 1; i < path.getLength(); i++) {
					Path.Step step = path.getStep(i);
					robot.enqueueMove(step.getX(), step.getY());
				}
			}
		}
	}
	
	private TileMap mapWithRobots() {
		TileMap map2 = new TileMap(map);
		for (MobileRobot robot : robots) {
			map2.setCell(robot.getPosition(), true);
			map2.setCell(robot.nearestTarget(), true);
		}
		return map2;
	}
	
	public synchronized void stepSimulation() {
		for (MobileRobot robot : robots) {
			if (robot.hasNextMove()) {
				robot.setPosition(robot.pollNextMove());
			}
			if (robot.hasReachedTarget() && params.robotAutoTarget) {
				robot.targetReached();
			}
			MobileRobot collidedRobot = collisionDetected(robot);
			if (collidedRobot != null || (!robot.hasNextMove() && !robot.hasReachedTarget())) {
				findPath(robot);
			}
		}
	}
	
	public MobileRobot collisionDetected(MobileRobot robot) {
		for (MobileRobot otherRobot : robots) {
			if (otherRobot == robot)
				continue;
			if (otherRobot.getPosition().equals(robot.nearestTarget()) || otherRobot.nearestTarget()
					.equals(robot.nearestTarget())) {
				// throw new CollisionDetectedException(); // collisions frequency tests
				return otherRobot;
			}
		}
		return null;
	}
	
	public class CollisionDetectedException extends RuntimeException {
	
	}
}
