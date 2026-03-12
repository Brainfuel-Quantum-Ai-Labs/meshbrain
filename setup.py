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
        "aiohttp>=3.9.0",
        "websockets>=12.0",
        "cryptography>=41.0.0",
        "numpy>=1.26.0",
        "requests>=2.31.0",
        "fastapi>=0.109.0",
        "uvicorn>=0.27.0",
        "pydantic>=2.5.0",
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
