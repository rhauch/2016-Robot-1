/*
 * Copyright (c) 2016 FRC Team 4931
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

/* Created Sun Jan 10 12:59:55 CST 2016 */
package org.frc4931.robot;

import edu.wpi.first.wpilibj.IterativeRobot;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import org.frc4931.robot.arm.Arm;
import org.frc4931.robot.arm.CalibrateArm;
import org.frc4931.robot.arm.LowerArmWhile;
import org.frc4931.robot.arm.MoveArmTo;
import org.frc4931.robot.arm.RaiseArmWhile;
import org.frc4931.robot.components.IMU;
import org.frc4931.robot.components.InfraredSensor;
import org.frc4931.robot.drive.DriveSystem;
import org.frc4931.robot.drive.TimedDrive;
import org.frc4931.robot.math.PIDGains;
import org.frc4931.robot.roller.Roller;
import org.frc4931.robot.roller.SpitWhile;
import org.frc4931.robot.roller.SuckWhile;
import org.strongback.Strongback;
import org.strongback.SwitchReactor;
import org.strongback.components.DistanceSensor;
import org.strongback.components.Motor;
import org.strongback.components.Switch;
import org.strongback.components.TalonSRX;
import org.strongback.components.ui.ContinuousRange;
import org.strongback.components.ui.FlightStick;
import org.strongback.control.TalonController;
import org.strongback.drive.TankDrive;
import org.strongback.hardware.Hardware;

public class Robot extends IterativeRobot {
    private static final String LOG_FILES_DIRECTORY_PATH = "/home/lvuser/frc4931";

    private static final int LEFT_FRONT_MOTOR_PWM_CHANNEL = 2;
    private static final int LEFT_REAR_MOTOR_PWM_CHANNEL = 3;
    private static final int RIGHT_FRONT_MOTOR_PWM_CHANNEL = 0;
    private static final int RIGHT_REAR_MOTOR_PWM_CHANNEL = 1;
    private static final int IR_SENSOR_A_DIO_CHANNEL = 2;
    private static final int IR_SENSOR_B_DIO_CHANNEL = 3;
    private static final int ROLLER_MOTOR_CAN_ID = 0;
    private static final int ARM_MOTOR_CAN_ID = 1;
    private static final int FORWARD_AIN_CHANNEL = 0;
    private static final double FORWARD_INCHES_PER_VOLT = 102.4;

    // 7 pulses per rev; 71:1 motor gearing ratio; 28:12 sprocket ratio; 360 degrees per rev
    private static final double ARM_PULSES_PER_DEGREE = 3.2213;
    public static final double ARM_HOME_ANGLE = 0.0;
    public static final double ARM_LOW_ANGLE = 190.0;
    public static final double ARM_HIGH_ANGLE = 130.0;
    public static final PIDGains ARM_DEFAULT_GAINS = new PIDGains(47.4, 0.015, 0.0);

    public static final double AUTO_DRIVE_SPEED=1;
    public static final double AUTO_DRIVE_TIME=2;

    private DriveSystem drive;
    private Arm arm;
    private Roller roller;
    private ContinuousRange driveSpeed;
    private ContinuousRange turnSpeed;
    private double driveScale = 1.0;

    @Override
    public void robotInit() {
        Strongback.configure()
//                  .recordNoData().recordNoEvents().recordNoCommands();
                  .recordDataToFile(LOG_FILES_DIRECTORY_PATH)
                  .recordEventsToFile(LOG_FILES_DIRECTORY_PATH, 2097152);

        // Define the motors and the drive system ...
        Motor leftFrontMotor = Hardware.Motors.victorSP(LEFT_FRONT_MOTOR_PWM_CHANNEL);
        Motor leftRearMotor = Hardware.Motors.victorSP(LEFT_REAR_MOTOR_PWM_CHANNEL);
        Motor rightFrontMotor = Hardware.Motors.victorSP(RIGHT_FRONT_MOTOR_PWM_CHANNEL);
        Motor rightRearMotor = Hardware.Motors.victorSP(RIGHT_REAR_MOTOR_PWM_CHANNEL);
        Motor leftMotors = Motor.compose(leftFrontMotor, leftRearMotor);
        Motor rightMotors = Motor.compose(rightFrontMotor, rightRearMotor).invert();
        TankDrive tankDrive = new TankDrive(leftMotors, rightMotors);
        IMU imu = IMU.stationary();
        DistanceSensor forward = Hardware.DistanceSensors.analogUltrasonic(FORWARD_AIN_CHANNEL, FORWARD_INCHES_PER_VOLT);
        drive = new DriveSystem(tankDrive, imu, forward);

        // Initialize the subsystems ...
        TalonController armMotor = Hardware.Controllers.talonController(ARM_MOTOR_CAN_ID, ARM_PULSES_PER_DEGREE, 0.0);
        armMotor.setFeedbackDevice(TalonSRX.FeedbackDevice.QUADRATURE_ENCODER);
        armMotor.reverseOutput(true);
        armMotor.reverseSensor(true);
//        armMotor.setForwardSoftLimit(0);
//        armMotor.setReverseSoftLimit(205);
//        armMotor.setForwardSoftLimit(-120);
//        armMotor.setReverseSoftLimit(-40);
        arm = new Arm(armMotor);
        arm.setGains(ARM_DEFAULT_GAINS);
        arm.setSoftLimitsEnabled(false);

        Motor rollerMotor = Hardware.Motors.talonSRX(ROLLER_MOTOR_CAN_ID);
        InfraredSensor iSA=new InfraredSensor(IR_SENSOR_A_DIO_CHANNEL);
        InfraredSensor iSB=new InfraredSensor(IR_SENSOR_B_DIO_CHANNEL);
        roller = new Roller(rollerMotor,iSA,iSB);

//        CameraServer.getInstance().setQuality(50);
//        CameraServer.getInstance().startAutomaticCapture(new USBCamera());

        // Define the interface components ...
        FlightStick driverJoystick = Hardware.HumanInterfaceDevices.logitechAttack3D(0);
        ContinuousRange throttle = driverJoystick.getThrottle().map(t -> (1.0 - t) / 2);
        driveSpeed = driverJoystick.getPitch().scale(throttle::read).scale(() -> driveScale).invert();
        turnSpeed = driverJoystick.getYaw().scale(throttle::read);
        Switch flipDirection = driverJoystick.getTrigger();
        Switch armUp = driverJoystick.getButton(6);
        Switch armDown = driverJoystick.getButton(4);
		Switch suck = driverJoystick.getButton(3);
        Switch spit = driverJoystick.getButton(5);

        FlightStick coDriverJoystick = Hardware.HumanInterfaceDevices.logitechAttack3D(1);
        Switch armUpB = coDriverJoystick.getButton(3);
        Switch armDownB = coDriverJoystick.getButton(2);
        Switch armLow = coDriverJoystick.getButton(7);
        Switch armHigh = coDriverJoystick.getButton(6);
        Switch raisePortcullis = coDriverJoystick.getButton(12); // If other PID constants are needed

        // Register the functions that run when the switches change state ...
        SwitchReactor reactor = Strongback.switchReactor();

        reactor.onTriggered(flipDirection, () -> driveScale *= -1.0);
		reactor.onTriggeredSubmit(suck, () -> new SuckWhile(roller, suck));
        reactor.onTriggeredSubmit(spit, () -> new SpitWhile(roller, spit));
        reactor.onTriggeredSubmit(armUp, () -> new RaiseArmWhile(arm, armUp));
        reactor.onTriggeredSubmit(armDown, () -> new LowerArmWhile(arm, armDown));
        reactor.onTriggeredSubmit(driverJoystick.getThumb(), () -> new CalibrateArm(arm));

        reactor.onTriggeredSubmit(armUpB, () -> new RaiseArmWhile(arm, armUpB));
        reactor.onTriggeredSubmit(armDownB, () -> new LowerArmWhile(arm, armDownB));
        reactor.onTriggeredSubmit(armLow, () -> new MoveArmTo(arm, ARM_LOW_ANGLE));
        reactor.onTriggeredSubmit(armHigh, () -> new MoveArmTo(arm, ARM_HIGH_ANGLE));

        // Set up the data recorder to capture the left & right motor speeds and the sensivity.
        // We have to do this before we start Strongback...
//       Strongback.dataRecorder()
//                 .register("Left motors", leftMotors)
//                 .register("Right motors", rightMotors)
//                 .register("Sensitivity", throttle.scaleAsInt(1000))
//                 .register("Arm Angle", ()-> (int)(100*arm.getCurrentAngle()));
    }

    @Override
    public void autonomousInit() {
        // Start Strongback functions ...
        Strongback.restart();
        Strongback.submit(new TimedDrive(drive,AUTO_DRIVE_SPEED,0,AUTO_DRIVE_TIME));
    }

    @Override
    public void autonomousPeriodic() {
    }

    @Override
    public void teleopInit() {
        // Kill anything running, and start it ...
        Strongback.restart();
        Strongback.submit(new CalibrateArm(arm));
    }

    @Override
    public void teleopPeriodic() {
        drive.arcade(driveSpeed.read(), turnSpeed.read());

        SmartDashboard.putNumber("Arm Angle", arm.getCurrentAngle());
        SmartDashboard.putNumber("Arm Target", arm.getTargetAngle());
        SmartDashboard.putBoolean("Arm At Home", arm.isAtHome());

        double forwardDistance = drive.getForwardProximity().getDistanceInInches();
        SmartDashboard.putNumber("Forward Distance", forwardDistance);
        SmartDashboard.putBoolean("Distance < 12in", forwardDistance < 12.0);
    }

    @Override
    public void disabledInit() {
        // Tell Strongback that the robot is disabled so it can flush and kill commands.
        Strongback.disable();
    }

    @Override
    public void testInit() {
        Strongback.restart();
    }
}
