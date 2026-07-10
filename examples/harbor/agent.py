"""Local-development Harbor adapter for the packaged karcarthy ACP demo.

Harbor's public ACP registry intentionally requires HTTPS distribution URLs.
This adapter changes only the installation step: it uploads a locally built
archive into the task environment, then delegates execution, logging, and ATIF
conversion to Harbor's generic AcpAgent implementation.
"""

from pathlib import Path
from typing import Any

from harbor.agents.installed import acp as harbor_acp
from harbor.agents.installed.acp import AcpAgent


class Agent(AcpAgent):
    def __init__(self, archive_path: str, *args: Any, **kwargs: Any) -> None:
        self._local_archive_path = Path(archive_path).resolve()
        if not self._local_archive_path.is_file():
            raise ValueError(
                f"karcarthy ACP archive does not exist: {self._local_archive_path}"
            )

        target = {
            "archive": "https://example.invalid/karcarthy-harbor.tgz",
            "cmd": "./karcarthy",
        }
        registry_entry = {
            "id": "karcarthy-hill-climb",
            "name": "karcarthy hill-climbing demo",
            "version": "0.0.2",
            "description": "Locally packaged runtime-generated Clojure Agents",
            "distribution": {
                "binary": {
                    "linux-aarch64": target,
                    "linux-x86_64": target,
                }
            },
        }
        super().__init__(registry_entry=registry_entry, *args, **kwargs)

    async def _install_binary_target(self, environment: Any, target: Any) -> None:
        remote_archive = "/tmp/karcarthy-harbor.tgz"
        await environment.upload_file(
            source_path=self._local_archive_path,
            target_path=remote_archive,
        )
        await self.exec_as_root(
            environment,
            command=f"""
set -euo pipefail
rm -rf {self._BINARY_INSTALL_DIR}
mkdir -p {self._BINARY_INSTALL_DIR}/dist
tar -xzf {remote_archive} -C {self._BINARY_INSTALL_DIR}/dist
chmod -R a+rX {self._BINARY_INSTALL_DIR}
""".strip(),
        )

    async def install(self, environment: Any) -> None:
        """Install only the local archive; the task image carries ACP Python."""
        await self._ensure_registry_entry()
        platform_id = await self._detect_platform(environment)
        kind, target = self._select_distribution(platform_id)
        self._selected_distribution_kind = kind
        await self._install_binary_target(environment, target)

        launcher_path = self.logs_dir / "acp-launch.sh"
        launcher_path.write_text(self._build_launcher_script(kind, target))
        await environment.upload_file(
            source_path=launcher_path,
            target_path=self._LAUNCHER_REMOTE_PATH,
        )
        await environment.exec(
            command=f"chmod +x {self._LAUNCHER_REMOTE_PATH}", user="root"
        )

        runner_path = Path(harbor_acp.__file__).with_name("acp_runner.py")
        await environment.upload_file(
            source_path=runner_path,
            target_path=self._RUNNER_REMOTE_PATH,
        )
        await environment.exec(
            command=f"chmod +x {self._RUNNER_REMOTE_PATH}", user="root"
        )
