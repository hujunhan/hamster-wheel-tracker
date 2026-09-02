import argparse
import json
import logging
from typing import List, Optional

import uvicorn

from hamster_tracker.runtime import RuntimeSettings
from hamster_tracker.web.app import create_app


LOGGER = logging.getLogger("hamster_tracker.runtime")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="hamster-tracker")
    subparsers = parser.add_subparsers(dest="command", required=True)

    def add_runtime_options(command: argparse.ArgumentParser) -> None:
        command.add_argument("--config", help="Calibration/config JSON path")
        command.add_argument("--database", help="SQLite database path")
        command.add_argument("--host", help="Web bind address, e.g. 0.0.0.0")
        command.add_argument("--port", type=int, help="Web server port")
        command.add_argument(
            "--log-level",
            choices=["critical", "error", "warning", "info", "debug", "trace"],
            help="Runtime/Uvicorn log level",
        )

    serve = subparsers.add_parser("serve", help="Run the persistent tracker web service")
    add_runtime_options(serve)

    doctor = subparsers.add_parser(
        "doctor",
        help="Validate persistent paths/config/database without requiring camera hardware",
    )
    add_runtime_options(doctor)

    return parser


def _settings(args: argparse.Namespace) -> RuntimeSettings:
    return RuntimeSettings.resolve(
        config_path=args.config,
        database_path=args.database,
        host=args.host,
        port=args.port,
        log_level=args.log_level,
    )


def run_serve(args: argparse.Namespace) -> int:
    settings = _settings(args)
    settings.prepare()

    logging.basicConfig(
        level=getattr(logging, settings.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    LOGGER.info(
        "starting hamster tracker host=%s port=%d config=%s database=%s camera=not_configured",
        settings.host,
        settings.port,
        settings.config_path,
        settings.database_path,
    )

    app = create_app(
        database_path=str(settings.database_path),
        config_path=str(settings.config_path),
    )
    app.state.runtime_settings = settings.as_dict()
    uvicorn.run(
        app,
        host=settings.host,
        port=settings.port,
        log_level=settings.log_level,
    )
    return 0


def run_doctor(args: argparse.Namespace) -> int:
    settings = _settings(args)
    report = settings.doctor()
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report.get("ok") else 1


def main(argv: Optional[List[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "serve":
            return run_serve(args)
        if args.command == "doctor":
            return run_doctor(args)
    except (OSError, ValueError) as exc:
        parser.error(str(exc))
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
