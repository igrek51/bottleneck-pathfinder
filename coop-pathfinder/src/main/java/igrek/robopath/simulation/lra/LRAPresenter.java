package igrek.robopath.simulation.lra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.LinkedList;
import java.util.List;

import de.felixroske.jfxsupport.FXMLController;
import igrek.robopath.common.Point;
import igrek.robopath.common.TileMap;
import igrek.robopath.simulation.common.ResizableCanvas;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

@FXMLController
@Qualifier("lraPresenter")
public class LRAPresenter {
	
	private final double FPS = 24;
	private final double MOVE_STEP_DURATION = 500;
	
	private LRAController controller;
	private Logger logger = LoggerFactory.getLogger(this.getClass());
	private Boolean pressedTransformer;
	private long lastSimulationTime;
	
	@FXML
	private ResizableCanvas drawArea;
	@FXML
	private VBox drawAreaContainer;
	
	@Autowired
	@Qualifier("lraParams")
	private LRASimulationParams params;
	@FXML
	public TextField paramMapSizeW;
	@FXML
	public TextField paramMapSizeH;
	@FXML
	public TextField paramRobotsCount;
	@FXML
	public CheckBox paramRobotAutoTarget;
	
	
	@Autowired
	public void setController(@Qualifier("lraController") LRAController controller) {
		this.controller = controller;
	}
	
	@FXML
	public void initialize() {
		Platform.runLater(() -> { // fixing fxml retarded initialization
			try {
				logger.info("initializing " + this.getClass().getSimpleName());
				
				Thread.sleep(100); // FIXME still view isn't guaranteed to be initialized :(
				
				drawAreaContainerResized();
				drawAreaContainer.widthProperty().addListener(o -> drawAreaContainerResized());
				drawAreaContainer.heightProperty().addListener(o -> drawAreaContainerResized());
				
				drawArea.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
					mousePressedMap(event);
				});
				drawArea.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
					mouseDraggedMap(event);
				});
				drawArea.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> {
					mouseReleased(event);
				});
				
				params.init(this);
				params.sendToUI();
				startSimulationTimer();
				startRepaintTimer();
			} catch (Throwable t) {
				logger.error(t.getMessage());
			}
		});
	}
	
	TileMap getMap() {
		return controller.getMap();
	}
	
	List<MobileRobot> getRobots() {
		return controller.getRobots();
	}
	
	@FXML
	private void resetMap(final Event event) {
		if (event != null)
			params.readFromUI();
		
		controller.resetMap();
		
		if (event != null)
			drawAreaContainerResized();
	}
	
	@FXML
	private void placeRobots(final Event event) {
		if (event != null)
			params.readFromUI();
		controller.placeRobots();
	}
	
	@FXML
	private void generateMaze(final Event event) {
		if (event != null)
			params.readFromUI();
		controller.generateMaze();
	}
	
	private void drawAreaContainerResized() {
		double containerWidth = drawAreaContainer.getWidth();
		double containerHeight = drawAreaContainer.getHeight();
		double maxCellW = containerWidth / getMap().getWidthInTiles();
		double maxCellH = containerHeight / getMap().getHeightInTiles();
		double cellSize = maxCellW < maxCellH ? maxCellW : maxCellH; // min
		drawArea.setWidth(cellSize * getMap().getWidthInTiles());
		drawArea.setHeight(cellSize * getMap().getHeightInTiles());
	}
	
	
	private void startRepaintTimer() {
		// animation timer
		Timeline timeline = new Timeline(new KeyFrame(Duration.millis(1000 / FPS), event -> repaint()));
		timeline.setCycleCount(Timeline.INDEFINITE);
		timeline.play();
	}
	
	private void startSimulationTimer() {
		new Thread(() -> {
			try {
				Timeline timeline = new Timeline(new KeyFrame(Duration.millis(MOVE_STEP_DURATION), event -> {
					try {
						if (controller != null) {
							controller.stepSimulation();
							lastSimulationTime = System.currentTimeMillis();
						}
					} catch (Throwable t) {
						logger.error(t.getMessage(), t);
					}
				}));
				timeline.setCycleCount(Timeline.INDEFINITE);
				timeline.play();
			} catch (Throwable t) {
				logger.error(t.getMessage(), t);
			}
		}).start();
	}
	
	private void mousePressedMap(MouseEvent event) {
		TileMap map = getMap();
		List<MobileRobot> robots = getRobots();
		
		if (event.getButton() == MouseButton.PRIMARY) {
			
			Point point = locatePoint(map, event);
			if (point != null) {
				Boolean state = map.getCell(point);
				if (!state) {
					MobileRobot occupiedBy = controller.occupiedByRobot(point);
					if (occupiedBy != null) {
						robots.remove(occupiedBy);
					} else {
						controller.createMobileRobot(point, robots.size());
					}
				}
				repaint();
			}
			
		} else if (event.getButton() == MouseButton.SECONDARY) {
			
			Point point = locatePoint(map, event);
			if (point != null) {
				Boolean state = !map.getCell(point);
				map.setCell(point, state);
				pressedTransformer = state;
				repaint();
			}
			
		}
	}
	
	private void mouseDraggedMap(MouseEvent event) {
		if (event.getButton() == MouseButton.SECONDARY) {
			TileMap map = getMap();
			Point point = locatePoint(map, event);
			if (point != null) {
				Boolean state = map.getCell(point);
				if (state != pressedTransformer) {
					map.setCell(point, pressedTransformer);
					repaint();
				}
			}
			
		}
	}
	
	private void mouseReleased(MouseEvent event) {
		TileMap map = getMap();
		
		if (event.getButton() == MouseButton.PRIMARY) {
			Point point = locatePoint(map, event);
			if (point != null) {
				synchronized (controller) {
					List<MobileRobot> robots = getRobots();
					if (!robots.isEmpty()) {
						MobileRobot lastRobot = robots.get(robots.size() - 1);
						lastRobot.setTarget(point);
						repaint();
					}
				}
			}
		}
	}
	
	@FXML
	private void randomTargetPressed(final Event event) {
		controller.randomTargetPressed();
	}
	
	@FXML
	private void eventReadParams(final Event event) {
		params.readFromUI();
	}
	
	
	//	VIEW
	void repaint() {
		drawMap();
	}
	
	private void drawMap() {
		GraphicsContext gc = drawArea.getGraphicsContext2D();
		if (controller != null) {
			drawGrid(gc);
			drawCells(gc);
			drawRobots(gc);
		}
	}
	
	private void drawCells(GraphicsContext gc) {
		TileMap map = getMap();
		map.foreach((x, y, occupied) -> drawCell(gc, x, y, occupied));
	}
	
	private void drawCell(GraphicsContext gc, int x, int y, boolean occupied) {
		TileMap map = getMap();
		double cellW = drawArea.getWidth() / map.getWidthInTiles();
		double cellH = drawArea.getHeight() / map.getHeightInTiles();
		double w2 = 0.95 * cellW;
		double h2 = 0.95 * cellH;
		if (occupied) {
			gc.setFill(Color.rgb(0, 0, 0));
			double x2 = x * cellW + (cellW - w2) / 2;
			double y2 = y * cellH + (cellH - h2) / 2;
			gc.fillRoundRect(x2, y2, w2, h2, w2 / 3, h2 / 3);
		}
	}
	
	private void drawGrid(GraphicsContext gc) {
		TileMap map = getMap();
		gc.clearRect(0, 0, drawArea.getWidth(), drawArea.getHeight());
		
		gc.setLineWidth(1);
		gc.setStroke(Color.rgb(200, 200, 200));
		// vertical lines
		for (int x = 0; x <= map.getWidthInTiles(); x++) {
			double x2 = x * drawArea.getWidth() / map.getWidthInTiles();
			gc.strokeLine(x2, 0, x2, drawArea.getHeight());
		}
		// horizontal lines
		for (int y = 0; y <= map.getHeightInTiles(); y++) {
			double y2 = y * drawArea.getHeight() / map.getHeightInTiles();
			gc.strokeLine(0, y2, drawArea.getWidth(), y2);
		}
	}
	
	Point locatePoint(TileMap map, MouseEvent event) {
		double screenX = event.getX();
		double screenY = event.getY();
		int mapX = ((int) (screenX * map.getWidthInTiles() / drawArea.getWidth()));
		int mapY = ((int) (screenY * map.getHeightInTiles() / drawArea.getHeight()));
		if (mapX < 0 || mapY < 0 || mapX >= map.getWidthInTiles() || mapY >= map.getHeightInTiles())
			return null;
		return new Point(mapX, mapY);
	}
	
	private void drawRobots(GraphicsContext gc) {
		double simulationStepProgress = (System.currentTimeMillis() - lastSimulationTime) / MOVE_STEP_DURATION;
		List<MobileRobot> robots = getRobots();
		int index = 0;
		for (MobileRobot robot : robots) {
			drawRobot(gc, robot, index++, simulationStepProgress);
		}
	}
	
	private void drawRobot(GraphicsContext gc, MobileRobot robot, int index, double stepProgress) {
		TileMap map = getMap();
		double cellW = drawArea.getWidth() / map.getWidthInTiles();
		double cellH = drawArea.getHeight() / map.getHeightInTiles();
		double w = 0.6 * cellW;
		double h = 0.6 * cellH;
		Color robotColor = robotColor(index);
		// draw target
		gc.setLineWidth(cellW / 18);
		if (robot.getTarget() != null) {
			Point target = robot.getTarget();
			gc.setStroke(robotColor);
			double targetX = target.getX() * cellW + cellW / 2;
			double targetY = target.getY() * cellH + cellH / 2;
			gc.strokeLine(targetX - w / 2, targetY - h / 2, targetX + w / 2, targetY + h / 2);
			gc.strokeLine(targetX - w / 2, targetY + h / 2, targetX + w / 2, targetY - h / 2);
		}
		// draw path
		gc.setStroke(robotColor);
		LinkedList<Point> movesQue = robot.getMovesQue();
		Point previous = robot.getPosition();
		for (Point move : movesQue) {
			double fromX = previous.getX() * cellW + cellW / 2;
			double fromY = previous.getY() * cellH + cellH / 2;
			double toX = move.getX() * cellW + cellW / 2;
			double toY = move.getY() * cellH + cellH / 2;
			gc.strokeLine(fromX, fromY, toX, toY);
			previous = move;
		}
		// draw robot
		gc.setFill(robotColor);
		double x = robot.getInterpolatedX(stepProgress) * cellW + cellW / 2 - w / 2;
		double y = robot.getInterpolatedY(stepProgress) * cellH + cellH / 2 - h / 2;
		gc.fillOval(x, y, w, h);
		// draw its priority
		gc.setFill(robotColor(index, 0.5));
		gc.setTextAlign(TextAlignment.CENTER);
		gc.setTextBaseline(VPos.CENTER);
		gc.setFont(new Font("System", h / 2));
		gc.fillText(Integer.toString(robot.getPriority() + 1), x + w / 2, y + h / 2);
	}
	
	private Color robotColor(int index) {
		return robotColor(index, 1);
	}
	
	private Color robotColor(int index, double b) {
		List<MobileRobot> robots = getRobots();
		double hue = 360.0 * index / robots.size();
		return Color.hsb(hue, 1, b);
	}
	
	@FXML
	private void buttonPathfind() {
		new Thread(() -> controller.findPaths()).start();
	}
}
