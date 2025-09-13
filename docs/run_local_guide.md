if you just want it working quickly without touching system Python:

1)Install or update Homebrew Python (optional but recommended)

- If you don’t have Homebrew: /bin/bash -c "$(curl
  -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
- Then: brew install python This gives you python3 and pip3 on your PATH

.2)Create and activate a virtual environment in your docs repo

- python3 -m venv .venv
- source .venv/bin/activate

Your shell prompt should show (.venv)

.3)Upgrade pip and install MkDocs + plugins

- python -m pip install --upgrade pip
- pip install mkdocs mkdocs-material mkdocs-mermaid2-plugin mkdocs-awesome-pages-plugin

4)Verify installation

- mkdocs --version

5)Serve your site locally

- From the directory containing mkdocs.yml: mkdocs serve
  Open http://127.0.0.1:8000/ (or the address shown in the terminal).