from setuptools import setup, find_packages

setup(
    name="meshbrain",
    version="0.1.0",
    description="Decentralized P2P AI — Every Device is a Local Brain",
    long_description=open("README.md").read(),
    long_description_content_type="text/markdown",
    author="MeshBrain Contributors",
    license="MIT",
    python_requires=">=3.10",
    packages=find_packages(),
    install_requires=[
        "aiohttp>=3.14.0",
        "websockets>=16.0",
        "cryptography>=41.0.7",
        "numpy>=2.0.0",
        "requests>=2.32.0",
        "fastapi>=0.139.0",
        "uvicorn>=0.35.0",
        "pydantic>=2.13.0",
    ],
    entry_points={
        "console_scripts": [
            "meshbrain=meshbrain.node:main",
        ],
    },
    classifiers=[
        "Development Status :: 3 - Alpha",
        "Intended Audience :: Developers",
        "Intended Audience :: Science/Research",
        "License :: OSI Approved :: MIT License",
        "Programming Language :: Python :: 3.10",
        "Programming Language :: Python :: 3.11",
        "Programming Language :: Python :: 3.12",
        "Topic :: Scientific/Engineering :: Artificial Intelligence",
        "Topic :: System :: Networking :: Monitoring",
    ],
    keywords="ai mesh p2p decentralized federated-learning blockchain privacy llm",
)
