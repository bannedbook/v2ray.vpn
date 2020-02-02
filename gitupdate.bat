git config --global core.autocrlf true
git pull origin master
git add -A
git commit -m "update"
git push origin master
git tag -a v5.0.3 -m "release v5.0.3"
git push origin --tags
pause